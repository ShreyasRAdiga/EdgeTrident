#include <jni.h>

#include <android/log.h>

#if LITEVISIONENGINE_HAS_NCNN
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <allocator.h>
#include <net.h>
#if NCNN_VULKAN
#include <gpu.h>
#endif
#endif

#include <atomic>
#include <algorithm>
#include <map>
#include <memory>
#include <mutex>
#include <new>
#include <sstream>
#include <string>
#include <vector>

namespace {

#if LITEVISIONENGINE_HAS_NCNN
constexpr const char* kLogTag = "LiteVisionEngine";
#endif

struct LoadedModel {
#if LITEVISIONENGINE_HAS_NCNN
    std::unique_ptr<ncnn::Net> net;
#endif
    std::string input_name;
    std::vector<std::string> output_names;
    int input_width = 0;
    int input_height = 0;
};

struct NativeEngine {
    explicit NativeEngine(int threads, bool vulkan, int max_frames)
        : num_threads(threads),
          prefer_vulkan(vulkan),
          max_in_flight_frames(max_frames) {
#if LITEVISIONENGINE_HAS_NCNN
        blob_pool_allocator.set_size_compare_ratio(0.0f);
        workspace_pool_allocator.set_size_compare_ratio(0.0f);
#endif
    }

    int num_threads;
    bool prefer_vulkan;
    int max_in_flight_frames;
    std::atomic<int> in_flight_frames{0};
#if LITEVISIONENGINE_HAS_NCNN
    ncnn::UnlockedPoolAllocator blob_pool_allocator;
    ncnn::PoolAllocator workspace_pool_allocator;
#endif
    std::mutex mutex;
    std::map<std::string, LoadedModel> models;
};

class ScopedFrameSlot {
public:
    explicit ScopedFrameSlot(NativeEngine* engine) : engine_(engine) {
        const int previous = engine_->in_flight_frames.fetch_add(1);
        acquired_ = previous < engine_->max_in_flight_frames;
        if (!acquired_) {
            engine_->in_flight_frames.fetch_sub(1);
        }
    }

    ~ScopedFrameSlot() {
        if (acquired_) {
            engine_->in_flight_frames.fetch_sub(1);
        }
    }

    bool acquired() const {
        return acquired_;
    }

private:
    NativeEngine* engine_;
    bool acquired_ = false;
};

NativeEngine* from_handle(jlong handle) {
    return reinterpret_cast<NativeEngine*>(handle);
}

#if LITEVISIONENGINE_HAS_NCNN
std::string to_string(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }

    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }

    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::vector<std::string> to_string_vector(JNIEnv* env, jobjectArray values) {
    std::vector<std::string> result;
    if (values == nullptr) {
        return result;
    }

    const jsize count = env->GetArrayLength(values);
    result.reserve(static_cast<size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(values, index));
        result.push_back(to_string(env, item));
        env->DeleteLocalRef(item);
    }
    return result;
}
#endif

jstring new_error(JNIEnv* env, const std::string& message) {
    return env->NewStringUTF(message.c_str());
}

void throw_java(JNIEnv* env, const char* class_name, const std::string& message) {
    jclass exception_class = env->FindClass(class_name);
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
    }
}

#if LITEVISIONENGINE_HAS_NCNN
jfloatArray empty_results(JNIEnv* env) {
    return env->NewFloatArray(0);
}

void configure_net_options(NativeEngine* engine, ncnn::Net* net, bool prefer_vulkan) {
    net->opt.lightmode = true;
    net->opt.num_threads = engine->num_threads;
    net->opt.blob_allocator = &engine->blob_pool_allocator;
    net->opt.workspace_allocator = &engine->workspace_pool_allocator;

#if NCNN_VULKAN
    net->opt.use_vulkan_compute = prefer_vulkan && ncnn::get_gpu_count() > 0;
#else
    if (prefer_vulkan) {
        __android_log_print(
            ANDROID_LOG_INFO,
            kLogTag,
            "Vulkan requested, but this NCNN build does not expose Vulkan support."
        );
    }
#endif
}

int ncnn_pixel_type_from_frame_format(jint pixel_format) {
    switch (pixel_format) {
        case 1:
            return ncnn::Mat::PIXEL_RGBA2RGB;
        case 2:
            return ncnn::Mat::PIXEL_RGB;
        default:
            return -1;
    }
}

ncnn::Mat create_inference_input(
    const LoadedModel& model,
    const void* frame_data,
    jint frame_width,
    jint frame_height,
    jint pixel_format
) {
    const int pixel_type = ncnn_pixel_type_from_frame_format(pixel_format);
    if (pixel_type < 0) {
        return {};
    }

    return ncnn::Mat::from_pixels_resize(
        static_cast<const unsigned char*>(frame_data),
        pixel_type,
        static_cast<int>(frame_width),
        static_cast<int>(frame_height),
        model.input_width,
        model.input_height
    );
}

void append_if_missing(std::vector<std::string>& values, const std::string& candidate) {
    if (candidate.empty()) {
        return;
    }

    if (std::find(values.begin(), values.end(), candidate) == values.end()) {
        values.push_back(candidate);
    }
}

std::vector<std::string> input_candidates(const LoadedModel& model) {
    std::vector<std::string> candidates;
    append_if_missing(candidates, model.input_name);
    append_if_missing(candidates, "images");
    append_if_missing(candidates, "in0");
    append_if_missing(candidates, "input");
    append_if_missing(candidates, "data");
    return candidates;
}

std::vector<std::string> output_candidates(const LoadedModel& model) {
    if (!model.output_names.empty()) {
        return model.output_names;
    }

    std::vector<std::string> candidates;
    append_if_missing(candidates, "out0");
    append_if_missing(candidates, "out1");
    append_if_missing(candidates, "out2");
    append_if_missing(candidates, "output0");
    append_if_missing(candidates, "output1");
    append_if_missing(candidates, "output2");
    append_if_missing(candidates, "output");
    return candidates;
}
#endif

jlong minimum_buffer_size(jint pixel_format, jint row_stride_bytes, jint height) {
    const bool is_yuv_420 = pixel_format == 3 || pixel_format == 4;
    const jint numerator = is_yuv_420 ? 3 : 1;
    const jint denominator = is_yuv_420 ? 2 : 1;
    const jlong rows = (
        static_cast<jlong>(height) * static_cast<jlong>(numerator) + denominator - 1
    ) / denominator;
    return static_cast<jlong>(row_stride_bytes) * rows;
}

bool direct_buffer_has_capacity(
    JNIEnv* env,
    jobject buffer,
    jint pixel_format,
    jint row_stride_bytes,
    jint height
) {
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (capacity < 0) {
        return false;
    }

    return capacity >= minimum_buffer_size(pixel_format, row_stride_bytes, height);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_adiga_shreyas_edgetrident_litevisionengine_NativeBridge_nativeCreate(
    JNIEnv*,
    jclass,
    jint num_threads,
    jboolean prefer_vulkan,
    jint max_in_flight_frames
) {
    auto* engine = new (std::nothrow) NativeEngine(
        static_cast<int>(num_threads),
        prefer_vulkan == JNI_TRUE,
        static_cast<int>(max_in_flight_frames)
    );
    if (engine == nullptr) {
        return 0L;
    }
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jstring JNICALL
Java_adiga_shreyas_edgetrident_litevisionengine_NativeBridge_nativeCapabilities(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    NativeEngine* engine = from_handle(handle);
    if (engine == nullptr) {
        return env->NewStringUTF("closed");
    }

    std::ostringstream stream;
    stream << "threads=" << engine->num_threads
           << ";preferVulkan=" << (engine->prefer_vulkan ? "true" : "false");

#if LITEVISIONENGINE_HAS_NCNN
    stream << ";ncnn=available";
#if NCNN_VULKAN
    const int gpu_count = ncnn::get_gpu_count();
    stream << ";vulkanBuild=true;gpuCount=" << gpu_count
           << ";vulkanRuntimeEnabled=" << (engine->prefer_vulkan && gpu_count > 0 ? "true" : "false");
#else
    stream << ";vulkanBuild=false;gpuCount=0;vulkanRuntimeEnabled=false";
#endif
#else
    stream << ";ncnn=missing;vulkanBuild=false;gpuCount=0;vulkanRuntimeEnabled=false";
#endif

    return env->NewStringUTF(stream.str().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_adiga_shreyas_edgetrident_litevisionengine_NativeBridge_nativeLoadModel(
    JNIEnv* env,
    jclass,
    jlong handle,
    jobject asset_manager,
    jstring model_id,
    jstring param_asset_path,
    jstring bin_asset_path,
    jstring input_name,
    jobjectArray output_names,
    jint input_width,
    jint input_height,
    jboolean prefer_vulkan
) {
#if LITEVISIONENGINE_HAS_NCNN
    NativeEngine* engine = from_handle(handle);
    if (engine == nullptr) {
        return new_error(env, "Native engine handle is closed.");
    }

    AAssetManager* assets = AAssetManager_fromJava(env, asset_manager);
    if (assets == nullptr) {
        return new_error(env, "Android AssetManager is unavailable.");
    }

    const std::string model_id_string = to_string(env, model_id);
    const std::string param_path = to_string(env, param_asset_path);
    const std::string bin_path = to_string(env, bin_asset_path);

    LoadedModel model;
    model.net = std::make_unique<ncnn::Net>();
    model.input_name = to_string(env, input_name);
    model.output_names = to_string_vector(env, output_names);
    model.input_width = static_cast<int>(input_width);
    model.input_height = static_cast<int>(input_height);

    configure_net_options(engine, model.net.get(), prefer_vulkan == JNI_TRUE);

    const int param_status = model.net->load_param(assets, param_path.c_str());
    if (param_status != 0) {
        return new_error(env, "ncnn::Net::load_param failed for asset '" + param_path + "'.");
    }

    const int model_status = model.net->load_model(assets, bin_path.c_str());
    if (model_status != 0) {
        return new_error(env, "ncnn::Net::load_model failed for asset '" + bin_path + "'.");
    }

    std::lock_guard<std::mutex> lock(engine->mutex);
    engine->models[model_id_string] = std::move(model);
    return nullptr;
#else
    (void)handle;
    (void)asset_manager;
    (void)model_id;
    (void)param_asset_path;
    (void)bin_asset_path;
    (void)input_name;
    (void)output_names;
    (void)input_width;
    (void)input_height;
    (void)prefer_vulkan;
    return new_error(
        env,
        "NCNN was not packaged with this build. Extract ncnn-20260113-android-vulkan.zip into "
        "LiteVisionEngine/src/main/cpp/ncnn/<ANDROID_ABI>/ before loading models."
    );
#endif
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_adiga_shreyas_edgetrident_litevisionengine_NativeBridge_nativeTrack(
    JNIEnv* env,
    jclass,
    jlong handle,
    jstring model_id,
    jobject buffer,
    jint width,
    jint height,
    jint pixel_format,
    jint row_stride_bytes,
    jlong timestamp_nanos,
    jint rotation_degrees
) {
    (void)timestamp_nanos;
    (void)rotation_degrees;

    NativeEngine* engine = from_handle(handle);
    if (engine == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "Native engine handle is closed.");
        return nullptr;
    }

    void* frame_data = env->GetDirectBufferAddress(buffer);
    if (frame_data == nullptr || !direct_buffer_has_capacity(env, buffer, pixel_format, row_stride_bytes, height)) {
        throw_java(env, "java/lang/IllegalArgumentException", "Frame buffer must be a direct ByteBuffer with enough capacity.");
        return nullptr;
    }

#if LITEVISIONENGINE_HAS_NCNN
    ScopedFrameSlot frame_slot(engine);
    if (!frame_slot.acquired()) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "Dropping frame because native processing is saturated.");
        return empty_results(env);
    }

    const std::string model_id_string = to_string(env, model_id);
    std::lock_guard<std::mutex> lock(engine->mutex);
    auto model = engine->models.find(model_id_string);
    if (model == engine->models.end()) {
        throw_java(env, "java/lang/IllegalStateException", "Requested model is not loaded.");
        return nullptr;
    }

    const LoadedModel& loaded_model = model->second;
    ncnn::Mat input_tensor = create_inference_input(
        loaded_model,
        frame_data,
        width,
        height,
        pixel_format
    );
    if (input_tensor.empty()) {
        throw_java(
            env,
            "java/lang/IllegalArgumentException",
            "Unsupported frame format for NCNN inference. Use RGBA_8888 or RGB_888."
        );
        return nullptr;
    }

    const std::vector<std::string> all_input_candidates = input_candidates(loaded_model);
    const std::vector<std::string> all_output_candidates = output_candidates(loaded_model);
    bool inference_executed = false;

    for (const std::string& input_name : all_input_candidates) {
        ncnn::Extractor extractor = loaded_model.net->create_extractor();
        extractor.set_light_mode(true);
        const int input_status = extractor.input(input_name.c_str(), input_tensor);
        if (input_status != 0) {
            continue;
        }

        for (const std::string& output_name : all_output_candidates) {
            ncnn::Mat output;
            const int extract_status = extractor.extract(output_name.c_str(), output);
            if (extract_status == 0) {
                inference_executed = true;
            }
        }
        if (inference_executed) {
            break;
        }
    }

    if (!inference_executed) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "NCNN forward pass failed. Check model input/output blob names."
        );
        return nullptr;
    }

    return empty_results(env);
#else
    (void)model_id;
    throw_java(
        env,
        "java/lang/IllegalStateException",
        "NCNN was not packaged with this build; extract the NCNN Android package before tracking."
    );
    return nullptr;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_adiga_shreyas_edgetrident_litevisionengine_NativeBridge_nativeRelease(
    JNIEnv*,
    jclass,
    jlong handle
) {
    delete from_handle(handle);
}
