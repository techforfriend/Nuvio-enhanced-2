import CoreVideo
import Metal
import simd

final class PictureInPictureDolbyVisionConverter {
    private struct Params {
        var yOffset: Float
        var yScale: Float
        var cOffset: Float
        var cScale: Float
        var step: UInt32
    }

    private struct PoolKey: Equatable {
        let width: Int
        let height: Int
    }

    private static let shaderSource = """
    #include <metal_stdlib>
    using namespace metal;

    struct Params {
        float yOffset;
        float yScale;
        float cOffset;
        float cScale;
        uint step;
    };

    constant float m1 = 0.1593017578125;
    constant float m2 = 78.84375;
    constant float c1 = 0.8359375;
    constant float c2 = 18.8515625;
    constant float c3 = 18.6875;

    static float3 pqToLinear(float3 e) {
        float3 p = pow(max(e, 0.0), 1.0 / m2);
        float3 num = max(p - c1, 0.0);
        float3 den = max(c2 - c3 * p, 1e-6);
        return pow(num / den, 1.0 / m1);
    }

    static float3 linearToPq(float3 y) {
        float3 yp = pow(clamp(y, 0.0, 1.0), m1);
        return pow((c1 + c2 * yp) / (1.0 + c3 * yp), m2);
    }

    kernel void dolbyVisionToPq(
        texture2d<float, access::read> inY [[texture(0)]],
        texture2d<float, access::read> inC [[texture(1)]],
        texture2d<float, access::write> outY [[texture(2)]],
        texture2d<float, access::write> outC [[texture(3)]],
        constant float3x3 &iptToLms [[buffer(0)]],
        constant float3x3 &lmsToRgb [[buffer(1)]],
        constant Params &p [[buffer(2)]],
        uint2 gid [[thread_position_in_grid]]
    ) {
        if (gid.x >= outC.get_width() || gid.y >= outC.get_height()) return;
        uint2 inYMax = uint2(inY.get_width() - 1, inY.get_height() - 1);
        uint2 inCMax = uint2(inC.get_width() - 1, inC.get_height() - 1);
        float2 chromaSum = float2(0.0);
        float count = 0.0;
        for (uint dy = 0; dy < 2; dy++) {
            for (uint dx = 0; dx < 2; dx++) {
                uint2 o = gid * 2 + uint2(dx, dy);
                if (o.x >= outY.get_width() || o.y >= outY.get_height()) continue;
                uint2 src = min(o * p.step, inYMax);
                float luma = inY.read(src).r;
                float2 chroma = inC.read(min(src / 2, inCMax)).rg;
                float3 ipt = float3((luma - p.yOffset) / p.yScale, (chroma - p.cOffset) / p.cScale);
                float3 rgb = linearToPq(lmsToRgb * pqToLinear(iptToLms * ipt));
                float y = dot(rgb, float3(0.2627, 0.6780, 0.0593));
                outY.write(float4(64.0 / 1023.0 + y * (876.0 / 1023.0)), o);
                chromaSum += float2((rgb.b - y) / 1.8814, (rgb.r - y) / 1.4746);
                count += 1.0;
            }
        }
        float2 chroma = chromaSum / max(count, 1.0);
        outC.write(float4(512.0 / 1023.0 + chroma * (896.0 / 1023.0), 0.0, 1.0), gid);
    }
    """

    private static let iptToLms = simd_float3x3(
        SIMD3<Float>(1.0, 1.0, 1.0),
        SIMD3<Float>(0.0975689, -0.1138760, 0.0326151),
        SIMD3<Float>(0.205226, 0.133217, -0.676887)
    )

    private static let lmsToRgb: simd_float3x3 = {
        let lmsToRgb = simd_float3x3(rows: [
            SIMD3<Float>(3.06441879, -2.16597676, 0.10155818),
            SIMD3<Float>(-0.65612108, 1.78554118, -0.12943749),
            SIMD3<Float>(0.01736321, -0.04725154, 1.03004253),
        ])
        let c: Float = 0.02
        let crosstalk = simd_float3x3(rows: [
            SIMD3<Float>(1 - 2 * c, c, c),
            SIMD3<Float>(c, 1 - 2 * c, c),
            SIMD3<Float>(c, c, 1 - 2 * c),
        ])
        return lmsToRgb * crosstalk.inverse
    }()

    private static let maxOutputEdge = 1920

    private let device: MTLDevice?
    private var queue: MTLCommandQueue?
    private var pipeline: MTLComputePipelineState?
    private var textureCache: CVMetalTextureCache?
    private var setupFailed = false
    private var pool: CVPixelBufferPool?
    private var poolKey: PoolKey?

    init() {
        device = MTLCreateSystemDefaultDevice()
    }

    func convert(_ source: CVPixelBuffer) -> CVPixelBuffer? {
        guard prepare(), let queue, let pipeline, let textureCache else { return nil }
        let format = CVPixelBufferGetPixelFormatType(source)
        guard CVPixelBufferGetPlaneCount(source) == 2,
              let layout = Self.layout(for: format) else { return nil }

        let sourceWidth = CVPixelBufferGetWidth(source)
        let sourceHeight = CVPixelBufferGetHeight(source)
        let step = max(sourceWidth, sourceHeight) > Self.maxOutputEdge ? 2 : 1
        let width = max(2, (sourceWidth / step) & ~1)
        let height = max(2, (sourceHeight / step) & ~1)
        guard let output = makeBuffer(width: width, height: height) else { return nil }

        guard let inY = texture(source, plane: 0, format: layout.lumaFormat, cache: textureCache),
              let inC = texture(source, plane: 1, format: layout.chromaFormat, cache: textureCache),
              let outY = texture(output, plane: 0, format: .r16Unorm, cache: textureCache),
              let outC = texture(output, plane: 1, format: .rg16Unorm, cache: textureCache),
              let commandBuffer = queue.makeCommandBuffer(),
              let encoder = commandBuffer.makeComputeCommandEncoder() else { return nil }

        var iptToLms = Self.iptToLms
        var lmsToRgb = Self.lmsToRgb
        var params = Params(
            yOffset: layout.yOffset,
            yScale: layout.yScale,
            cOffset: layout.cOffset,
            cScale: layout.cScale,
            step: UInt32(step)
        )
        encoder.setComputePipelineState(pipeline)
        encoder.setTexture(CVMetalTextureGetTexture(inY), index: 0)
        encoder.setTexture(CVMetalTextureGetTexture(inC), index: 1)
        encoder.setTexture(CVMetalTextureGetTexture(outY), index: 2)
        encoder.setTexture(CVMetalTextureGetTexture(outC), index: 3)
        encoder.setBytes(&iptToLms, length: MemoryLayout<simd_float3x3>.stride, index: 0)
        encoder.setBytes(&lmsToRgb, length: MemoryLayout<simd_float3x3>.stride, index: 1)
        encoder.setBytes(&params, length: MemoryLayout<Params>.stride, index: 2)

        let chromaWidth = width / 2
        let chromaHeight = height / 2
        let threads = MTLSize(width: 16, height: 16, depth: 1)
        let groups = MTLSize(
            width: (chromaWidth + threads.width - 1) / threads.width,
            height: (chromaHeight + threads.height - 1) / threads.height,
            depth: 1
        )
        encoder.dispatchThreadgroups(groups, threadsPerThreadgroup: threads)
        encoder.endEncoding()
        commandBuffer.commit()
        commandBuffer.waitUntilCompleted()
        guard commandBuffer.status == .completed else { return nil }

        CVBufferSetAttachment(output, kCVImageBufferColorPrimariesKey, kCVImageBufferColorPrimaries_ITU_R_2020, .shouldPropagate)
        CVBufferSetAttachment(output, kCVImageBufferTransferFunctionKey, kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ, .shouldPropagate)
        CVBufferSetAttachment(output, kCVImageBufferYCbCrMatrixKey, kCVImageBufferYCbCrMatrix_ITU_R_2020, .shouldPropagate)
        return output
    }

    private struct Layout {
        let lumaFormat: MTLPixelFormat
        let chromaFormat: MTLPixelFormat
        let yOffset: Float
        let yScale: Float
        let cOffset: Float
        let cScale: Float
    }

    private static func layout(for format: OSType) -> Layout? {
        switch format {
        case kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange:
            return Layout(lumaFormat: .r16Unorm, chromaFormat: .rg16Unorm,
                          yOffset: 64 / 1023, yScale: 876 / 1023, cOffset: 512 / 1023, cScale: 896 / 1023)
        case kCVPixelFormatType_420YpCbCr10BiPlanarFullRange:
            return Layout(lumaFormat: .r16Unorm, chromaFormat: .rg16Unorm,
                          yOffset: 0, yScale: 1, cOffset: 512 / 1023, cScale: 1)
        case kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange:
            return Layout(lumaFormat: .r8Unorm, chromaFormat: .rg8Unorm,
                          yOffset: 16 / 255, yScale: 219 / 255, cOffset: 128 / 255, cScale: 224 / 255)
        case kCVPixelFormatType_420YpCbCr8BiPlanarFullRange:
            return Layout(lumaFormat: .r8Unorm, chromaFormat: .rg8Unorm,
                          yOffset: 0, yScale: 1, cOffset: 128 / 255, cScale: 1)
        default:
            return nil
        }
    }

    private func prepare() -> Bool {
        if pipeline != nil { return true }
        guard !setupFailed, let device else { return false }
        do {
            let library = try device.makeLibrary(source: Self.shaderSource, options: nil)
            guard let function = library.makeFunction(name: "dolbyVisionToPq") else {
                setupFailed = true
                return false
            }
            pipeline = try device.makeComputePipelineState(function: function)
            queue = device.makeCommandQueue()
            var cache: CVMetalTextureCache?
            CVMetalTextureCacheCreate(kCFAllocatorDefault, nil, device, nil, &cache)
            textureCache = cache
        } catch {
            setupFailed = true
            return false
        }
        guard queue != nil, textureCache != nil else {
            setupFailed = true
            return false
        }
        return true
    }

    private func texture(
        _ buffer: CVPixelBuffer,
        plane: Int,
        format: MTLPixelFormat,
        cache: CVMetalTextureCache
    ) -> CVMetalTexture? {
        var texture: CVMetalTexture?
        let status = CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault,
            cache,
            buffer,
            nil,
            format,
            CVPixelBufferGetWidthOfPlane(buffer, plane),
            CVPixelBufferGetHeightOfPlane(buffer, plane),
            plane,
            &texture
        )
        return status == kCVReturnSuccess ? texture : nil
    }

    private func makeBuffer(width: Int, height: Int) -> CVPixelBuffer? {
        let key = PoolKey(width: width, height: height)
        if key != poolKey {
            let attributes: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
                kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
                kCVPixelBufferMetalCompatibilityKey as String: true,
            ]
            let poolAttributes: [String: Any] = [kCVPixelBufferPoolMinimumBufferCountKey as String: 3]
            var newPool: CVPixelBufferPool?
            guard CVPixelBufferPoolCreate(kCFAllocatorDefault, poolAttributes as CFDictionary,
                                          attributes as CFDictionary, &newPool) == kCVReturnSuccess else {
                return nil
            }
            pool = newPool
            poolKey = key
        }
        guard let pool else { return nil }
        var buffer: CVPixelBuffer?
        guard CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &buffer) == kCVReturnSuccess else {
            return nil
        }
        return buffer
    }
}
