#pragma once

// Lets the Android/Fire TV layer (remote, Alexa voice, front ends) open and close the
// Enhancements menu and see whether it is open. Safe to call from any thread.
namespace SohTv {

enum class MenuRequest : int {
    None = 0,
    Open = 1,
    Close = 2,
    Toggle = 3,
    NextTab = 4,      // these open the menu first if needed
    PrevTab = 5,
    NextSection = 6,
    PrevSection = 7,
};

void PostMenuRequest(MenuRequest request);
MenuRequest TakeMenuRequest();   // game thread
void SetMenuOpen(bool open);     // game thread, once per frame
bool IsMenuOpen();

// Feed every connected controller (TV remote's virtual pad + Bluetooth gamepads) to menu
// navigation instead of only the first one. Game thread, after the ImGui backend is up.
void EnsureAllGamepadsNavigate();

} // namespace SohTv
