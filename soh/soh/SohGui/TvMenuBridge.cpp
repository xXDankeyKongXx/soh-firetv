#include "TvMenuBridge.h"

#include <atomic>

namespace {
std::atomic<int> sPendingRequest{ static_cast<int>(SohTv::MenuRequest::None) };
std::atomic<bool> sMenuOpen{ false };
} // namespace

namespace SohTv {

void PostMenuRequest(MenuRequest request) {
    sPendingRequest.store(static_cast<int>(request));
}

MenuRequest TakeMenuRequest() {
    return static_cast<MenuRequest>(sPendingRequest.exchange(static_cast<int>(MenuRequest::None)));
}

void SetMenuOpen(bool open) {
    sMenuOpen.store(open);
}

bool IsMenuOpen() {
    return sMenuOpen.load();
}

} // namespace SohTv

#ifdef __ANDROID__
#include <jni.h>
#include <imgui_impl_sdl2.h>

void SohTv::EnsureAllGamepadsNavigate() {
    static bool done = false;
    if (!done) {
        done = true;
        // The mobile backend defaults to AutoFirst, so only one pad could drive the menu.
        ImGui_ImplSDL2_SetGamepadMode(ImGui_ImplSDL2_GamepadMode_AutoAll);
    }
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_dishii_soh_MainActivity_nativeIsMenuOpen(JNIEnv*, jobject) {
    return SohTv::IsMenuOpen() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL Java_com_dishii_soh_MainActivity_nativeMenuRequest(JNIEnv*, jobject, jint request) {
    if (request >= 1 && request <= 7) {
        SohTv::PostMenuRequest(static_cast<SohTv::MenuRequest>(request));
    }
}
#else
void SohTv::EnsureAllGamepadsNavigate() {
}
#endif
