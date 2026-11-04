#include "Menu.h"
#include "UIWidgets.hpp"
#include "soh/OTRGlobals.h"
#include "soh/Enhancements/controls/SohInputEditorWindow.h"
#include "window/gui/GuiMenuBar.h"
#include "window/gui/GuiElement.h"
#include "SohModals.h"
#include "TvMenuBridge.h"
#ifdef __ANDROID__
#include "port/mobile/MobileImpl.h"
#endif
#include <variant>
#include <spdlog/fmt/fmt.h>
#include "variables.h"
#include <tuple>

extern "C" {
#include "z64.h"
#include "functions.h"
extern PlayState* gPlayState;
}
std::vector<ImVec2> windowTypeSizes = { {} };

extern std::unordered_map<s16, const char*> warpPointSceneList;
extern void Warp();

namespace SohGui {
extern std::shared_ptr<SohModalWindow> mModalWindow;
}

namespace Ship {
std::string disabledTempTooltip;
const char* disabledTooltip;
bool disabledValue = false;

bool operator==(Color_RGB8 const& l, Color_RGB8 const& r) noexcept {
    return l.r == r.r && l.g == r.g && l.b == r.b;
}

bool operator==(Color_RGBA8 const& l, Color_RGBA8 const& r) noexcept {
    return l.r == r.r && l.g == r.g && l.b == r.b && l.a == r.a;
}

bool operator<(Color_RGB8 const& l, Color_RGB8 const& r) noexcept {
    return (l.r < r.r && l.g <= r.g && l.b <= r.b) || (l.r <= r.r && l.g < r.g && l.b <= r.b) ||
           (l.r <= r.r && l.g <= r.g && l.b < r.b);
}

bool operator<(Color_RGBA8 const& l, Color_RGBA8 const& r) noexcept {
    return (l.r < r.r && l.g <= r.g && l.b <= r.b && l.a <= r.a) ||
           (l.r <= r.r && l.g < r.g && l.b <= r.b && l.a <= r.a) ||
           (l.r <= r.r && l.g <= r.g && l.b < r.b && l.a <= r.a) ||
           (l.r <= r.r && l.g <= r.g && l.b <= r.b && l.a < r.a);
}

bool operator>(Color_RGB8 const& l, Color_RGB8 const& r) noexcept {
    return (l.r > r.r && l.g >= r.g && l.b >= r.b) || (l.r >= r.r && l.g > r.g && l.b >= r.b) ||
           (l.r >= r.r && l.g >= r.g && l.b > r.b);
}

bool operator>(Color_RGBA8 const& l, Color_RGBA8 const& r) noexcept {
    return (l.r > r.r && l.g >= r.g && l.b >= r.b && l.a >= r.a) ||
           (l.r >= r.r && l.g > r.g && l.b >= r.b && l.a >= r.a) ||
           (l.r >= r.r && l.g >= r.g && l.b > r.b && l.a >= r.a) ||
           (l.r >= r.r && l.g >= r.g && l.b >= r.b && l.a > r.a);
}

uint32_t GetVectorIndexOf(std::vector<std::string>& vector, std::string value) {
    return std::distance(vector.begin(), std::find(vector.begin(), vector.end(), value));
}

static bool raceDisableActive = false;

// ---- Controller / TV remote navigation state ----
static bool sMenuWasVisible = false;          // detects the frame the menu opens
static bool sPopupOrEditLastFrame = false;    // a combo/modal/slider/text field used B last frame
static bool sFocusActiveHeader = false;       // move the nav cursor to the selected header tab
static bool sFocusActiveSection = false;      // move the nav cursor to the selected sidebar entry
static bool sDefaultFocusPending = false;     // menu just appeared: make the selected tab the default focus
static bool sNavInNestedChildLastFrame = false; // B was used by ImGui to leave a nested child
static int sRequestedHeaderDir = 0;           // tab/section steps requested by voice commands
static int sRequestedSectionDir = 0;
static bool sGameInputHeldAfterClose = false; // keep the B that closed the menu out of the game
static constexpr int32_t kMenuCloseInputBlockId = 0x54564D; // 'TVM'

// Close the menu from a controller button without the still-held button reaching the game.
static void BlockGameInputUntilReleased() {
    Ship::Context::GetInstance()->GetControlDeck()->BlockGameInput(kMenuCloseInputBlockId);
    sGameInputHeldAfterClose = true;
}

// Re-evaluate gamepad navigation and the touch area after the menu opens or closes.
static void RefreshNavAfterMenuToggle() {
    auto gui = Ship::Context::GetInstance()->GetWindow()->GetGui();
    auto& io = ImGui::GetIO();
    if (CVarGetInteger(CVAR_IMGUI_CONTROLLER_NAV, 0) && gui->GetMenuOrMenubarVisible()) {
        io.ConfigFlags |= ImGuiConfigFlags_NavEnableGamepad;
    } else {
        io.ConfigFlags &= ~ImGuiConfigFlags_NavEnableGamepad;
    }
#ifdef __ANDROID__
    if (gui->GetMenuOrMenubarVisible()) {
        Ship::Mobile::DisableTouchArea();
    } else {
        Ship::Mobile::EnableTouchArea();
    }
#endif
}

// Put the nav cursor on the item that was just submitted.
static void FocusLastItem() {
    ImGui::SetFocusID(ImGui::GetItemID(), ImGui::GetCurrentWindow());
    ImGui::SetNavCursorVisibleAfterMove(); // also marks the item as highlighted-by-nav
    ImGui::SetScrollHereX(0.5f);
    ImGui::SetScrollHereY(0.5f);
}

// Step through a list of labels with wrap-around. Returns the current label if not found.
static std::string CycleLabel(const std::vector<std::string>& order, const std::string& current, int dir) {
    if (order.empty()) {
        return current;
    }
    auto it = std::find(order.begin(), order.end(), current);
    if (it == order.end()) {
        return order.front(); // unknown/stale selection: land on the first entry
    }
    int count = static_cast<int>(order.size());
    int index = static_cast<int>(std::distance(order.begin(), it));
    index = ((index + dir) % count + count) % count;
    return order.at(index);
}

void Menu::InsertSidebarSearch() {
    menuEntries["Settings"].sidebars.emplace("Search", searchSidebarEntry);
    uint32_t curIndex = 0;
    if (!Ship_IsCStringEmpty(CVarGetString(menuEntries["Settings"].sidebarCvar, ""))) {
        curIndex = GetVectorIndexOf(menuEntries["Settings"].sidebarOrder,
                                    CVarGetString(menuEntries["Settings"].sidebarCvar, ""));
    }
    menuEntries["Settings"].sidebarOrder.insert(menuEntries["Settings"].sidebarOrder.begin() + searchSidebarIndex,
                                                "Search");
    if (curIndex > searchSidebarIndex) {
        CVarSetString(menuEntries["Settings"].sidebarCvar, menuEntries["Settings"].sidebarOrder.at(curIndex).c_str());
    }
}

void Menu::RemoveSidebarSearch() {
    uint32_t curIndex = GetVectorIndexOf(menuEntries["Settings"].sidebarOrder,
                                         CVarGetString(menuEntries["Settings"].sidebarCvar, "General"));
    menuEntries["Settings"].sidebars.erase("Search");
    std::erase_if(menuEntries["Settings"].sidebarOrder, [](std::string& name) { return name == "Search"; });
    if (curIndex > searchSidebarIndex) {
        curIndex--;
    } else if (curIndex >= menuEntries["Settings"].sidebarOrder.size()) {
        curIndex = menuEntries["Settings"].sidebarOrder.size() - 1;
    }
    CVarSetString(menuEntries["Settings"].sidebarCvar, menuEntries["Settings"].sidebarOrder.at(curIndex).c_str());
}

void Menu::UpdateWindowBackendObjects() {
    Ship::WindowBackend runningWindowBackend = Ship::Context::GetInstance()->GetWindow()->GetWindowBackend();
    int32_t configWindowBackendId = Ship::Context::GetInstance()->GetConfig()->GetInt("Window.Backend.Id", -1);
    if (Ship::Context::GetInstance()->GetWindow()->IsAvailableWindowBackend(configWindowBackendId)) {
        configWindowBackend = static_cast<Ship::WindowBackend>(configWindowBackendId);
    } else {
        configWindowBackend = runningWindowBackend;
    }

    availableWindowBackends = Ship::Context::GetInstance()->GetWindow()->GetAvailableWindowBackends();
    for (auto& backend : *availableWindowBackends) {
        availableWindowBackendsMap[backend] = windowBackendsMap.at(backend);
    }
}

bool Menu::IsMenuPopped() {
    return popped;
}

UIWidgets::Colors Menu::GetMenuThemeColor() {
    return menuThemeIndex;
}

Menu::Menu(const std::string& cVar, const std::string& name, uint8_t searchSidebarIndex_,
           UIWidgets::Colors defaultThemeIndex_)
    : GuiWindow(cVar, name), searchSidebarIndex(searchSidebarIndex_), defaultThemeIndex(defaultThemeIndex_) {
}

void Menu::InitElement() {
    popped = CVarGetInteger(CVAR_SETTING("Menu.Popout"), 0);
    poppedSize.x = CVarGetInteger(CVAR_SETTING("Menu.PoppedWidth"), 1280);
    poppedSize.y = CVarGetInteger(CVAR_SETTING("Menu.PoppedHeight"), 800);
    poppedPos.x = CVarGetInteger(CVAR_SETTING("Menu.PoppedPos.x"), 0);
    poppedPos.y = CVarGetInteger(CVAR_SETTING("Menu.PoppedPos.y"), 0);

    UpdateWindowBackendObjects();
}

void Menu::UpdateElement() {
    menuThemeIndex = static_cast<UIWidgets::Colors>(CVarGetInteger(CVAR_SETTING("Menu.Theme"), defaultThemeIndex));
}

bool ModernMenuSidebarEntry(std::string label) {
    ImGuiContext& g = *GImGui;
    ImGuiWindow* window = g.CurrentWindow;
    ImGuiStyle& style = ImGui::GetStyle();
    ImVec2 pos = window->DC.CursorPos;
    const ImGuiID sidebarId = window->GetID(std::string(label + "##Sidebar").c_str());
    ImVec2 labelSize = ImGui::CalcTextSize(label.c_str(), ImGui::FindRenderedTextEnd(label.c_str()), true);
    pos.y += style.FramePadding.y;
    pos.x = window->WorkRect.GetCenter().x - labelSize.x / 2;
    ImRect bb = { pos - style.FramePadding, pos + labelSize + style.FramePadding };
    ImGui::ItemSize(bb, style.FramePadding.y);
    ImGui::ItemAdd(bb, sidebarId);
    bool hovered, held;
    bool pressed = ImGui::ButtonBehavior(bb, sidebarId, &hovered, &held);
    if (pressed) {
        ImGui::MarkItemEdited(sidebarId);
    }
    ImGui::RenderNavCursor(bb, sidebarId); // visible focus frame for controller/remote
    window->DrawList->AddRectFilled(pos - style.FramePadding, pos + labelSize + style.FramePadding,
                                    ImGui::GetColorU32((held && hovered) ? ImGuiCol_ButtonActive
                                                       : hovered         ? ImGuiCol_ButtonHovered
                                                                         : ImGuiCol_Button),
                                    3.0f);
    UIWidgets::RenderText(pos, label.c_str(), ImGui::FindRenderedTextEnd(label.c_str()), true);
    return pressed;
}

bool ModernMenuHeaderEntry(std::string label) {
    ImGuiContext& g = *GImGui;
    ImGuiWindow* window = g.CurrentWindow;
    ImGuiStyle& style = ImGui::GetStyle();
    ImVec2 pos = window->DC.CursorPos;
    const ImGuiID headerId = window->GetID(std::string(label + "##Header").c_str());
    ImVec2 labelSize = ImGui::CalcTextSize(label.c_str(), ImGui::FindRenderedTextEnd(label.c_str()), true);
    ImRect bb = { pos, pos + labelSize + style.FramePadding * 2 };
    ImGui::ItemSize(bb, style.FramePadding.y);
    ImGui::ItemAdd(bb, headerId);
    bool hovered, held;
    bool pressed = ImGui::ButtonBehavior(bb, headerId, &hovered, &held);
    ImGui::RenderNavCursor(bb, headerId); // visible focus frame for controller/remote
    window->DrawList->AddRectFilled(bb.Min, bb.Max,
                                    ImGui::GetColorU32((held && hovered) ? ImGuiCol_ButtonActive
                                                       : hovered         ? ImGuiCol_ButtonHovered
                                                                         : ImGuiCol_Button),
                                    3.0f);
    pos += style.FramePadding;
    UIWidgets::RenderText(pos, label.c_str(), ImGui::FindRenderedTextEnd(label.c_str()), true);
    return pressed;
}

uint32_t Menu::DrawSearchResults(std::string& menuSearchText) {
    ImGui::BeginChild("Search Results", ImVec2(0, 0), ImGuiChildFlags_NavFlattened);
    int searchCount = 0;
    for (auto& menuLabel : menuOrder) {
        auto& menuEntry = menuEntries.at(menuLabel);
        for (auto& sidebarLabel : menuEntry.sidebarOrder) {
            auto& sidebar = menuEntry.sidebars[sidebarLabel];
            for (int i = 0; i < sidebar.columnWidgets.size(); i++) {
                auto& column = sidebar.columnWidgets.at(i);
                for (auto& info : column) {
                    if (info.type == WIDGET_SEARCH || info.type == WIDGET_SEPARATOR ||
                        info.type == WIDGET_SEPARATOR_TEXT || info.isHidden) {
                        continue;
                    }
                    const char* tooltip = info.options->tooltip;
                    std::string widgetStr = std::string(info.name) + std::string(tooltip != NULL ? tooltip : "");
                    std::transform(menuSearchText.begin(), menuSearchText.end(), menuSearchText.begin(), ::tolower);
                    menuSearchText.erase(std::remove(menuSearchText.begin(), menuSearchText.end(), ' '),
                                         menuSearchText.end());
                    std::transform(widgetStr.begin(), widgetStr.end(), widgetStr.begin(), ::tolower);
                    widgetStr.erase(std::remove(widgetStr.begin(), widgetStr.end(), ' '), widgetStr.end());
                    if (widgetStr.find(menuSearchText) != std::string::npos) {
                        MenuDrawItem(info, 90 / sidebar.columnCount, menuThemeIndex);
                        ImGui::PushStyleColor(ImGuiCol_Text, UIWidgets::ColorValues.at(UIWidgets::Colors::Gray));
                        std::string origin = fmt::format("  ({} -> {}, Col {})", menuEntry.label, sidebarLabel, i + 1);
                        ImGui::Text("%s", origin.c_str());
                        ImGui::PopStyleColor();
                        searchCount++;
                    }
                }
            }
        }
    }
    return searchCount;
}

void Menu::AddMenuEntry(std::string entryName, const char* entryCvar) {
    menuEntries.emplace(entryName, MainMenuEntry{ entryName, entryCvar });
    menuOrder.push_back(entryName);
}

std::unordered_map<uint32_t, disabledInfo>& Menu::GetDisabledMap() {
    return disabledMap;
}

void Menu::MenuDrawItem(WidgetInfo& widget, uint32_t width, UIWidgets::Colors menuThemeIndex) {
    disabledTempTooltip = "This setting is disabled because: \n";
    disabledValue = false;
    disabledTooltip = " ";

    if (widget.preFunc != nullptr) {
        widget.ResetDisables();
        widget.preFunc(widget);
        if (widget.isHidden) {
            return;
        }
        if (!widget.activeDisables.empty()) {
            widget.options->disabled = true;
            for (auto option : widget.activeDisables) {
                disabledTempTooltip += std::string("\n- ") + disabledMap.at(option).reason;
            }
            widget.options->disabledTooltip = disabledTempTooltip.c_str();
        }
    }
    if (widget.raceDisable && raceDisableActive) {
        widget.options->disabled = true;
        disabledTempTooltip += std::string("\n- Race Lockout Active");
        widget.options->disabledTooltip = disabledTempTooltip.c_str();
    }

    if (widget.sameLine) {
        ImGui::SameLine();
    }

    try {
        switch (widget.type) {
            case WIDGET_CHECKBOX: {
                bool* pointer = std::get<bool*>(widget.valuePointer);
                if (pointer == nullptr) {
                    SPDLOG_ERROR("Checkbox Widget requires a value pointer, currently nullptr");
                    assert(false);
                    return;
                }
                auto options = std::static_pointer_cast<UIWidgets::CheckboxOptions>(widget.options);
                options->color = menuThemeIndex;
                if (UIWidgets::Checkbox(UIWidgets::WrappedText(widget.name.c_str(), width).c_str(), pointer,
                                        *options)) {
                    if (widget.callback != nullptr) {
                        widget.callback(widget);
                    }
                }
            } break;
            case WIDGET_CVAR_CHECKBOX: {
                auto options = std::static_pointer_cast<UIWidgets::CheckboxOptions>(widget.options);
                options->color = menuThemeIndex;
                if (UIWidgets::CVarCheckbox(UIWidgets::WrappedText(widget.name.c_str(), width).c_str(), widget.cVar,
                                            *options)) {
                    if (widget.callback != nullptr) {
                        widget.callback(widget);
                    }
                };
            } break;
            case WIDGET_AUDIO_BACKEND: {
                auto currentAudioBackend = Ship::Context::GetInstance()->GetAudio()->GetCurrentAudioBackend();
                UIWidgets::ComboboxOptions options = {};
                options.color = menuThemeIndex;
                options.tooltip = "Sets the audio API used by the game. Requires a relaunch to take effect.";
                options.disabled = Ship::Context::GetInstance()->GetAudio()->GetAvailableAudioBackends()->size() <= 1;
                options.disabledTooltip = "Only one audio API is available on this platform.";
                if (UIWidgets::Combobox("Audio API", &currentAudioBackend, audioBackendsMap, options)) {
                    Ship::Context::GetInstance()->GetAudio()->SetCurrentAudioBackend(currentAudioBackend);
                }
            } break;
            case WIDGET_VIDEO_BACKEND: {
                UIWidgets::ComboboxOptions options = {};
                options.color = menuThemeIndex;
                options.tooltip = "Sets the renderer API used by the game.";
                options.disabled = availableWindowBackends->size() <= 1;
                options.disabledTooltip = "Only one renderer API is available on this platform.";
                if (UIWidgets::Combobox("Renderer API (Needs reload)", &configWindowBackend, availableWindowBackendsMap,
                                        options)) {
                    Ship::Context::GetInstance()->GetConfig()->SetInt("Window.Backend.Id",
                                                                      (int32_t)(configWindowBackend));
                    Ship::Context::GetInstance()->GetConfig()->SetString("Window.Backend.Name",
                                                                         windowBackendsMap.at(configWindowBackend));
                    Ship::Context::GetInstance()->GetConfig()->Save();
                    UpdateWindowBackendObjects();
                }
            } break;
            case WIDGET_SEPARATOR: {
                ImGui::Separator();
            } break;
            case WIDGET_SEPARATOR_TEXT: {
                auto options = std::static_pointer_cast<UIWidgets::TextOptions>(widget.options);
                if (options->color != UIWidgets::Colors::NoColor) {
                    ImGui::PushStyleColor(ImGuiCol_Text, UIWidgets::ColorValues.at(options->color));
                }
                ImGui::SeparatorText(widget.name.c_str());
                if (options->color != UIWidgets::Colors::NoColor) {
                    ImGui::PopStyleColor();
                }
            } break;
            case WIDGET_TEXT: {
                auto options = std::static_pointer_cast<UIWidgets::TextOptions>(widget.options);
                if (options->color != UIWidgets::Colors::NoColor) {
                    ImGui::PushStyleColor(ImGuiCol_Text, UIWidgets::ColorValues.at(options->color));
                }
                ImGui::AlignTextToFramePadding();
                ImGui::TextWrapped("%s", widget.name.c_str());
                if (options->color != UIWidgets::Colors::NoColor) {
                    ImGui::PopStyleColor();
                }
            } break;
            case WIDGET_COMBOBOX: {
                int32_t* pointer = std::get<int32_t*>(widget.valuePointer);
                if (pointer == nullptr) {
                    SPDLOG_ERROR("Combobox Widget requires a value pointer, currently nullptr");
                    assert(false);
                    return;
                }
                auto options = std::static_pointer_cast<UIWidgets::ComboboxOptions>(widget.options);
                options->color = menuThemeIndex;
                if (UIWidgets::Combobox(widget.name.c_str(), pointer, options->comboMap, *options)) {
                    if (widget.callback != nullptr) {
                        widget.callback(widget);
                    }
                };
            } break;
            case WIDGET_CVAR_COMBOBOX: {
                auto options = std::static_pointer_cast<UIWidgets::ComboboxOptions>(widget.options);
                options->color = menuThemeIndex;
                if (UIWidgets::CVarCombobox(widget.name.c_str(), widget.cVar, options->comboMap, *options)) {
                    if (widget.callback != nullptr) {
                        widget.callback(widget);
                    }
                }
            } break;
            case WIDGET_SLIDER_INT: {
                int32_t* pointer = std::get<int32_t*>(widget.valuePointer);
                if (pointer == nullptr) {
                    SPDLOG_ERROR("int32 Slider Widget requires a value pointer, currently nullptr");
                    assert(false);
                    return;
                }
                auto options = std::static_pointer_cast<UIWidgets::IntSliderOptions>(widget.options);
                options->color = menuThemeIndex;
                if (UIWidgets::SliderInt(widget.name.c_str(), pointer, *options)) {
                    if (widget.callback != nullptr) {
                        widget.callback(widget);
                    }
                };
            } break;
            case WIDGET_CVAR_SLIDER_INT: {
                auto options = std::static_pointer_cast<UIWidgets::IntSliderOptions>(widget.options);
                options->color = menuThemeIndex;
                if (UIWidgets::CVarSliderInt(widget.name.c_str(), widget.cVar, *options)) {
                    if (widget.callback != nullptr) {
                        widget.callback(widget);
                    }
                };
            } break;
            case WIDGET_SLIDER_FLOAT: {
                float* pointer = std::get<float*>(widget.valuePointer);

                if (pointer == nullptr) {
                    SPDLOG_ERROR("float Slider Widget requires a value pointer, currently nullptr");
                    assert(false);
                    return;
                }
                auto options = std::static_pointer_cast<UIWidgets::FloatSliderOptions>(widget.options);
                options->color = menuThemeIndex;
                if (UIWidgets::SliderFloat(widget.name.c_str(), pointer, *options)) {
                    if (widget.callback != nullptr) {
                        widget.callback(widget);
                    }
                }
            } break;
            case WIDGET_CVAR_SLIDER_FLOAT: {
                auto options = std::static_pointer_cast<UIWidgets::FloatSliderOptions>(widget.options);
                options->color = menuThemeIndex;
                if (UIWidgets::CVarSliderFloat(widget.name.c_str(), widget.cVar, *options)) {
                    if (widget.callback != nullptr) {
                        widget.callback(widget);
                    }
                }
            } break;
            case WIDGET_BUTTON: {
                auto options = std::static_pointer_cast<UIWidgets::ButtonOptions>(widget.options);
                options->color = menuThemeIndex;
                if (UIWidgets::Button(widget.name.c_str(), *options)) {
                    if (widget.callback != nullptr) {
                        widget.callback(widget);
                    }
                }
            } break;
            case WIDGET_CUSTOM: {
                if (widget.customFunction != nullptr) {
                    widget.customFunction(widget);
                }
            } break;
            case WIDGET_WINDOW_BUTTON: {
                if (widget.windowName == nullptr || widget.windowName[0] == '\0') {
                    std::string msg =
                        fmt::format("Error drawing window contents for {}: windowName not defined", widget.name);
                    SPDLOG_ERROR(msg.c_str());
                    break;
                }
                auto window = Ship::Context::GetInstance()->GetWindow()->GetGui()->GetGuiWindow(widget.windowName);
                if (!window) {
                    std::string msg =
                        fmt::format("Error drawing window contents: windowName {} does not exist", widget.windowName);
                    SPDLOG_ERROR(msg.c_str());
                    break;
                }
                auto options = std::static_pointer_cast<UIWidgets::WindowButtonOptions>(widget.options);
                options->color = menuThemeIndex;
                if (options->showButton) {
                    UIWidgets::WindowButton(widget.name.c_str(), widget.cVar, window, *options);
                }
                if (!window->IsVisible() && options->embedWindow) {
                    window->DrawElement();
                }
            } break;
            case WIDGET_SEARCH: {
                UIWidgets::PushStyleButton(menuThemeIndex);
                if (ImGui::Button("Clear")) {
                    menuSearch.Clear();
                }
                ImGui::SameLine();
                if (CVarGetInteger(CVAR_SETTING("Menu.SearchAutofocus"), 0) &&
                    ImGui::IsWindowFocused(ImGuiFocusedFlags_RootAndChildWindows) && !ImGui::IsAnyItemActive() &&
                    !ImGui::IsMouseClicked(0)) {
                    ImGui::SetKeyboardFocusHere(0);
                }
                UIWidgets::PushStyleCombobox(menuThemeIndex);
                ImGui::PushStyleColor(ImGuiCol_Border, UIWidgets::ColorValues.at(menuThemeIndex));
                menuSearch.Draw();
                ImGui::PopStyleColor();
                UIWidgets::PopStyleCombobox();
                UIWidgets::PopStyleButton();
                std::string menuSearchText(menuSearch.InputBuf);

                if (menuSearchText == "") {
                    ImGui::Text("Start typing to see results.");
                    return;
                }
                DrawSearchResults(menuSearchText);
                ImGui::EndChild();
            } break;
            default:
                break;
        }
        if (widget.postFunc != nullptr) {
            widget.postFunc(widget);
        }
    } catch (const std::bad_variant_access& e) {
        SPDLOG_ERROR("Failed to draw menu item \"{}\" due to: {}", widget.name, e.what());
        assert(false);
    }
}

void Menu::Draw() {
    SohTv::EnsureAllGamepadsNavigate();

    // Release the game-input block once the B that closed the menu is let go.
    if (sGameInputHeldAfterClose && !ImGui::IsKeyDown(ImGuiKey_GamepadFaceRight)) {
        Ship::Context::GetInstance()->GetControlDeck()->UnblockGameInput(kMenuCloseInputBlockId);
        sGameInputHeldAfterClose = false;
    }

    // Requests from the TV remote, Alexa voice commands or a front end.
    SohTv::MenuRequest request = SohTv::TakeMenuRequest();
    if (request != SohTv::MenuRequest::None) {
        bool want = IsVisible();
        switch (request) {
            case SohTv::MenuRequest::Open: want = true; break;
            case SohTv::MenuRequest::Close: want = false; break;
            case SohTv::MenuRequest::Toggle: want = !want; break;
            case SohTv::MenuRequest::NextTab: want = true; sRequestedHeaderDir = 1; break;
            case SohTv::MenuRequest::PrevTab: want = true; sRequestedHeaderDir = -1; break;
            case SohTv::MenuRequest::NextSection: want = true; sRequestedSectionDir = 1; break;
            case SohTv::MenuRequest::PrevSection: want = true; sRequestedSectionDir = -1; break;
            default: break;
        }
        if (want != IsVisible()) {
            ToggleVisibility();
            RefreshNavAfterMenuToggle();
        }
    }

    SohTv::SetMenuOpen(IsVisible());
    if (!IsVisible()) {
        sMenuWasVisible = false;
        return;
    }
    DrawElement();
    // Sync up the IsVisible flag if it was changed by ImGui
    SyncVisibilityConsoleVariable();

    sMenuWasVisible = IsVisible();
    sPopupOrEditLastFrame = ImGui::IsPopupOpen("", ImGuiPopupFlags_AnyPopupId | ImGuiPopupFlags_AnyPopupLevel) ||
                            ImGui::IsAnyItemActive();
    // Inside a nested (non-flattened) child, ImGui spends B on leaving it; don't also close the menu.
    ImGuiWindow* navWindow = GImGui->NavWindow;
    sNavInNestedChildLastFrame = navWindow != nullptr && navWindow->RootWindowForNav != navWindow->RootWindow;
    SohTv::SetMenuOpen(IsVisible());
}

void Menu::DrawElement() {
    for (auto& [reason, info] : disabledMap) {
        info.active = info.evaluation(info);
    }

    raceDisableActive = CVarGetInteger(CVAR_SETTING("DisableChanges"), 0);

    windowHeight = ImGui::GetMainViewport()->WorkSize.y;
    windowWidth = ImGui::GetMainViewport()->WorkSize.x;
    auto windowFlags = ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoSavedSettings;
    bool popout = CVarGetInteger(CVAR_SETTING("Menu.Popout"), 0) && allowPopout;
    if (popout) {
        windowFlags = ImGuiWindowFlags_NoScrollbar | ImGuiWindowFlags_NoDocking;
    }
    if (popout != popped) {
        if (popout) {
            windowHeight = poppedSize.y;
            windowWidth = poppedSize.x;
            ImGui::SetNextWindowSize({ static_cast<float>(windowWidth), static_cast<float>(windowHeight) },
                                     ImGuiCond_Always);
            ImGui::SetNextWindowPos(poppedPos, ImGuiCond_Always);
        } else if (popped) {
            CVarSetFloat(CVAR_SETTING("Menu.PoppedWidth"), poppedSize.x);
            CVarSetFloat(CVAR_SETTING("Menu.PoppedHeight"), poppedSize.y);
            CVarSave();
        }
    }
    popped = popout;
    auto windowCond = ImGuiCond_Always;
    if (!popout) {
        ImGui::SetNextWindowSize({ static_cast<float>(windowWidth), static_cast<float>(windowHeight) }, windowCond);
        ImGui::SetNextWindowPos(ImGui::GetMainViewport()->GetCenter(), windowCond, { 0.5f, 0.5f });
        ImGui::PushStyleVar(ImGuiStyleVar_WindowBorderSize, 0.0f);
    }

    ImGui::PushStyleColor(ImGuiCol_WindowBg,
                          ImVec4(0, 0, 0, CVarGetFloat(CVAR_SETTING("Menu.BackgroundOpacity"), 0.85f)));

    // When the menu opens, give it focus so a controller or remote can navigate it right away,
    // without touching or clicking it first.
    const bool justOpened = !sMenuWasVisible;
    if (justOpened) {
        ImGui::SetNextWindowFocus();
        sDefaultFocusPending = true;
    }

    if (!ImGui::Begin("Main Menu", NULL, windowFlags)) {
        if (!popout) {
            ImGui::PopStyleVar();
        }
        ImGui::PopStyleColor();
        ImGui::End();
        return;
    }

    ImGui::PopStyleColor();

    if (popped != popout) {
        if (!popout) {
            ImGui::PopStyleVar();
        }
        ImGui::PopStyleColor();
        CVarSetInteger(CVAR_SETTING("Menu.Popout"), popped);
        CVarSetFloat(CVAR_SETTING("Menu.PoppedWidth"), poppedSize.x);
        CVarSetFloat(CVAR_SETTING("Menu.PoppedHeight"), poppedSize.y);
        CVarSetFloat(CVAR_SETTING("Menu.PoppedPos.x"), poppedSize.x);
        CVarSetFloat(CVAR_SETTING("Menu.PoppedPos.y"), poppedSize.y);
        CVarSave();
        ImGui::End();
        return;
    }
    ImGuiContext& g = *GImGui;
    ImGuiWindow* window = g.CurrentWindow;
    ImGuiStyle& style = ImGui::GetStyle();
    windowHeight = window->WorkRect.GetHeight();
    windowWidth = window->WorkRect.GetWidth();

    // Controller shortcuts are only live when nothing else (combo, modal, slider, text box) is
    // using the buttons, so a B that closes a popup doesn't also close the menu.
    const bool gamepadNav = (ImGui::GetIO().ConfigFlags & ImGuiConfigFlags_NavEnableGamepad) != 0;
    const bool menuInputFree = gamepadNav && !justOpened && !sPopupOrEditLastFrame && !sNavInNestedChildLastFrame &&
                               ImGui::IsWindowFocused(ImGuiFocusedFlags_RootAndChildWindows) &&
                               !ImGui::IsPopupOpen("", ImGuiPopupFlags_AnyPopupId | ImGuiPopupFlags_AnyPopupLevel) &&
                               !ImGui::IsAnyItemActive();

    // B (remote: Back) with nothing to cancel closes the menu.
    if (menuInputFree && ImGui::IsKeyPressed(ImGuiKey_GamepadFaceRight, false)) {
        ToggleVisibility();
        RefreshNavAfterMenuToggle();
        BlockGameInputUntilReleased();
    }

    ImGui::PushFont(OTRGlobals::Instance->fontStandardLargest);
    ImGui::PushStyleVar(ImGuiStyleVar_FramePadding, ImVec2(10.0f, 8.0f));
    const char* headerCvar = CVAR_SETTING("Menu.ActiveHeader");
    std::string headerIndex = CVarGetString(headerCvar, "Settings");
    if (!menuEntries.contains(headerIndex) && !menuOrder.empty()) {
        headerIndex = menuOrder.at(0);
    }

    // LB/RB (remote: Play/Pause = next): switch the top tabs.
    if (menuInputFree) {
        int dir = ImGui::IsKeyPressed(ImGuiKey_GamepadR1, false)   ? 1
                  : ImGui::IsKeyPressed(ImGuiKey_GamepadL1, false) ? -1
                                                                   : 0;
        if (dir != 0) {
            sRequestedHeaderDir = dir;
        }
    }
    if (sRequestedHeaderDir != 0) {
        int dir = sRequestedHeaderDir;
        sRequestedHeaderDir = 0;
        {
            headerIndex = CycleLabel(menuOrder, headerIndex, dir);
            CVarSetString(headerCvar, headerIndex.c_str());
            CVarSave();
            if (!CVarGetInteger(CVAR_SETTING("Menu.SidebarSearch"), 0)) {
                menuSearch.Clear();
            }
            sFocusActiveHeader = true;
        }
    }
    ImVec2 pos = window->DC.CursorPos;
    float centerX = pos.x + windowWidth / 2 - (style.ItemSpacing.x * (menuEntries.size() + 1));
    std::vector<ImVec2> headerSizes;
    float headerWidth = style.ItemSpacing.x + 20;
    bool headerSearch = !CVarGetInteger(CVAR_SETTING("Menu.SidebarSearch"), 0);
    if (headerSearch) {
        headerWidth += 200.0f + style.ItemSpacing.x + style.FramePadding.x;
    }
    for (auto& label : menuOrder) {
        ImVec2 size = ImGui::CalcTextSize(label.c_str());
        headerSizes.push_back(size);
        headerWidth += size.x + style.FramePadding.x * 2;
        if (label == headerIndex) {
            headerWidth += style.ItemSpacing.x;
        }
    }

    // Full screen menu with widths below 1280, heights below 800.
    // 5% of screen width/height padding on both sides above those resolutions.
    // Menu width will never exceed a 16:9 aspect ratio.
    ImVec2 menuSize = { windowWidth, windowHeight };
    if (windowWidth > 1280) {
        menuSize.x = std::fminf(windowWidth * 0.9f, (windowHeight * 1.77f));
    }
    if (windowHeight > 800) {
        menuSize.y = windowHeight * 0.9f;
    }

    pos += window->WorkRect.GetSize() / 2 - menuSize / 2;
    ImGui::SetNextWindowPos(pos);
    ImGui::BeginChild("Menu Block", menuSize,
                      ImGuiChildFlags_AutoResizeX | ImGuiChildFlags_AutoResizeY | ImGuiChildFlags_AlwaysAutoResize |
                          ImGuiChildFlags_NavFlattened,
                      ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoScrollbar);

    std::unordered_map<std::string, SidebarEntry>* sidebar;
    float headerHeight = headerSizes.at(0).y + style.FramePadding.y * 2;
    ImVec2 buttonSize = ImGui::CalcTextSize(ICON_FA_TIMES_CIRCLE) + style.FramePadding * 2;
    bool scrollbar = false;
    if (headerWidth > menuSize.x - buttonSize.x * 3 - style.ItemSpacing.x * 3) {
        headerHeight += style.ScrollbarSize;
        scrollbar = true;
    }
    ImGui::SetNextWindowSizeConstraints({ 0, headerHeight }, { headerWidth, headerHeight });
    ImVec2 headerSelSize = { menuSize.x - buttonSize.x * 3 - style.ItemSpacing.x * 3, headerHeight };
    if (scrollbar) {
        headerSelSize.y += style.ScrollbarSize;
    }
    bool autoFocus = CVarGetInteger(CVAR_SETTING("Menu.SearchAutofocus"), 0);
    ImGui::BeginChild("Header Selection", headerSelSize,
                      ImGuiChildFlags_AutoResizeX | ImGuiChildFlags_AutoResizeY | ImGuiChildFlags_AlwaysAutoResize |
                          ImGuiChildFlags_NavFlattened,
                      ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_HorizontalScrollbar);
    uint8_t curIndex = 0;
    for (auto& label : menuOrder) {
        if (curIndex != 0) {
            ImGui::SameLine();
        }
        auto& entry = menuEntries.at(label);
        std::string nextIndex = label;
        UIWidgets::PushStyleButton(menuThemeIndex);
        if (headerIndex != label) {
            ImGui::PushStyleColor(ImGuiCol_Button, { 0, 0, 0, 0 });
        }
        bool headerPressed = ModernMenuHeaderEntry(entry.label);
        if (headerIndex == label && sDefaultFocusPending) {
            // On the opening frame ImGui picks a default nav item; make it the selected tab.
            ImGui::SetItemDefaultFocus();
            sDefaultFocusPending = false;
        } else if (headerIndex == label && sFocusActiveHeader) {
            FocusLastItem();
            sFocusActiveHeader = false;
        }
        if (headerPressed) {
            if (headerSearch) {
                menuSearch.Clear();
            }
            CVarSetString(headerCvar, label.c_str());
            CVarSave();
            nextIndex = label;
        }
        if (headerIndex != label) {
            ImGui::PopStyleColor();
        }
        UIWidgets::PopStyleButton();
        if (headerIndex == label) {
            sidebar = &entry.sidebars;
        }
        if (nextIndex != label) {
            headerIndex = nextIndex;
        }
        curIndex++;
    }
    std::string menuSearchText = "";
    if (headerSearch) {
        ImGui::SameLine();
        if (autoFocus && ImGui::IsWindowFocused(ImGuiFocusedFlags_RootAndChildWindows) && !ImGui::IsAnyItemActive() &&
            !ImGui::IsMouseClicked(0)) {
            ImGui::SetKeyboardFocusHere(0);
        }
        auto color = UIWidgets::ColorValues.at(menuThemeIndex);
        color.w = 0.2f;
        ImGui::PushStyleColor(ImGuiCol_FrameBg, color);
        menuSearch.Draw("##search", 200.0f);
        menuSearchText = menuSearch.InputBuf;
        menuSearchText.erase(std::remove(menuSearchText.begin(), menuSearchText.end(), ' '), menuSearchText.end());
        if (menuSearchText.length() < 1) {
            ImGui::SameLine(headerWidth - 200.0f + style.ItemSpacing.x);
            ImGui::TextColored(ImVec4(1.0f, 1.0f, 1.0f, 0.4f), "Search...");
        }
        ImGui::PopStyleColor();
    }
    ImGui::EndChild();
    ImGui::SameLine(menuSize.x - (buttonSize.x * 3) - (style.ItemSpacing.x * 2));
    UIWidgets::ButtonOptions options3 = {};
    options3.color = UIWidgets::Colors::Red;
    options3.size = UIWidgets::Sizes::Inline;
    options3.tooltip = "Quit SoH";
    if (UIWidgets::Button(ICON_FA_POWER_OFF, options3)) {
        SohGui::mModalWindow->RegisterPopup(
            "Quit SoH", "Are you sure you want to quit SoH?", "Quit", "Cancel",
            []() {
                std::shared_ptr<Menu> menu =
                    static_pointer_cast<Menu>(Ship::Context::GetInstance()->GetWindow()->GetGui()->GetMenu());
                if (!menu->IsMenuPopped()) {
                    menu->ToggleVisibility();
                }
                Ship::Context::GetInstance()->GetWindow()->Close();
            },
            nullptr);
    }
    ImGui::PopStyleVar();
    ImGui::SameLine();
    UIWidgets::ButtonOptions options2 = {};
    options2.color = UIWidgets::Colors::Red;
    options2.size = UIWidgets::Sizes::Inline;
    options2.tooltip = "Reset"
#ifdef __APPLE__
                       " (Command-R)"
#elif !defined(__SWITCH__) && !defined(__WIIU__)
                       " (Ctrl+R)"
#else
                       ""
#endif
        ;
    if (UIWidgets::Button(ICON_FA_UNDO, options2)) {
        std::reinterpret_pointer_cast<Ship::ConsoleWindow>(
            Ship::Context::GetInstance()->GetWindow()->GetGui()->GetGuiWindow("Console"))
            ->Dispatch("reset");
    }
    ImGui::SameLine();
    UIWidgets::ButtonOptions options = {};
    options.size = UIWidgets::Sizes::Inline;
    options.tooltip = "Close Menu (Esc)";
    if (UIWidgets::Button(ICON_FA_TIMES_CIRCLE, options)) {
        ToggleVisibility();
        // Update gamepad navigation (and the touch area) after close
        RefreshNavAfterMenuToggle();
    }

    pos.y += headerHeight + style.ItemSpacing.y;
    pos.x = centerX - menuSize.x / 2 + (style.ItemSpacing.x * (menuEntries.size() + 1));
    window->DrawList->AddRectFilled(pos, pos + ImVec2{ menuSize.x, 4 }, ImGui::GetColorU32({ 255, 255, 255, 255 }),
                                    true, style.WindowRounding);
    pos.y += style.ItemSpacing.y;
    float sectionHeight = menuSize.y - headerHeight - 4 - style.ItemSpacing.y * 2;
    float columnHeight = sectionHeight - style.ItemSpacing.y * 4;
    ImGui::SetNextWindowPos(pos + style.ItemSpacing * 2);

    // Increase sidebar width on larger screens to accomodate people scaling their menus.
    float sidebarWidth = 200 - style.ItemSpacing.x;
    if (menuSize.x > 1600) {
        sidebarWidth = menuSize.x * 0.15f;
    }

    const char* sidebarCvar = menuEntries.at(headerIndex).sidebarCvar;

    std::string sectionIndex = CVarGetString(sidebarCvar, "");
    if (!sidebar->contains(sectionIndex)) {
        sectionIndex = menuEntries.at(headerIndex).sidebarOrder.at(0);
    }

    // LT/RT (remote: Rewind/Fast-forward): previous/next sidebar section.
    if (menuInputFree) {
        int dir = ImGui::IsKeyPressed(ImGuiKey_GamepadR2, false)   ? 1
                  : ImGui::IsKeyPressed(ImGuiKey_GamepadL2, false) ? -1
                                                                   : 0;
        if (dir != 0) {
            sRequestedSectionDir = dir;
        }
    }
    if (sRequestedSectionDir != 0) {
        int dir = sRequestedSectionDir;
        sRequestedSectionDir = 0;
        {
            sectionIndex = CycleLabel(menuEntries.at(headerIndex).sidebarOrder, sectionIndex, dir);
            CVarSetString(sidebarCvar, sectionIndex.c_str());
            CVarSave();
            if (headerSearch) {
                menuSearch.Clear();
            }
            sFocusActiveSection = true;
        }
    }
    float sectionCenterX = pos.x + (sidebarWidth / 2);
    float topY = pos.y;
    ImGui::SetNextWindowSizeConstraints({ sidebarWidth, 0 }, { sidebarWidth, columnHeight });
    ImGui::BeginChild((menuEntries.at(headerIndex).label + " Section").c_str(), { sidebarWidth, columnHeight * 3 },
                      ImGuiChildFlags_AutoResizeY | ImGuiChildFlags_AlwaysAutoResize | ImGuiChildFlags_NavFlattened,
                      ImGuiWindowFlags_NoTitleBar);
    for (auto& sidebarLabel : menuEntries.at(headerIndex).sidebarOrder) {
        std::string nextIndex = "";
        UIWidgets::PushStyleButton(menuThemeIndex);
        if (sectionIndex != sidebarLabel) {
            ImGui::PushStyleColor(ImGuiCol_Button, { 0, 0, 0, 0 });
        }
        bool sidebarPressed = ModernMenuSidebarEntry(sidebarLabel);
        if (sectionIndex == sidebarLabel && sFocusActiveSection) {
            FocusLastItem();
            sFocusActiveSection = false;
        }
        if (sidebarPressed) {
            if (headerSearch) {
                menuSearch.Clear();
            }
            CVarSetString(sidebarCvar, sidebarLabel.c_str());
            CVarSave();
            nextIndex = sidebarLabel;
        }
        if (sectionIndex != sidebarLabel) {
            ImGui::PopStyleColor();
        }
        UIWidgets::PopStyleButton();
        if (nextIndex != "") {
            sectionIndex = nextIndex;
        }
    }
    ImGui::EndChild();
    ImGui::PopFont();

    pos = ImVec2{ sectionCenterX + (sidebarWidth / 2), topY } + style.ItemSpacing * 2;
    window->DrawList->AddRectFilled(pos, pos + ImVec2{ 4, sectionHeight - style.FramePadding.y * 2 },
                                    ImGui::GetColorU32({ 255, 255, 255, 255 }), true, style.WindowRounding);
    pos.x += 4 + style.ItemSpacing.x;
    ImGui::SetNextWindowPos(pos + style.ItemSpacing);
    float sectionWidth = menuSize.x - sidebarWidth - 4 - style.ItemSpacing.x * 4;
    std::string sectionMenuId = sectionIndex + " Settings";
    int columns = sidebar->at(sectionIndex).columnCount;
    size_t columnFuncs = sidebar->at(sectionIndex).columnWidgets.size();
    if (windowWidth < 800) {
        columns = 1;
    }
    float columnWidth = (sectionWidth - style.ItemSpacing.x * columns) / columns;
    bool useColumns = columns > 1;
    if (!useColumns || (headerSearch && menuSearchText.length() > 0)) {
        ImGui::SameLine();
        ImGui::SetNextWindowSizeConstraints({ sectionWidth, 0 }, { sectionWidth, columnHeight });
        ImGui::BeginChild(sectionMenuId.c_str(), { sectionWidth, windowHeight * 4 },
                          ImGuiChildFlags_AutoResizeY | ImGuiChildFlags_NavFlattened, ImGuiWindowFlags_NoTitleBar);
    }
    if (headerSearch && menuSearchText.length() > 0) {
        uint32_t searchCount = DrawSearchResults(menuSearchText);
        if (searchCount == 0) {
            ImGui::SetCursorPosX((ImGui::GetWindowWidth() - ImGui::CalcTextSize("No results found").x) / 2);
            ImGui::SetCursorPosY(ImGui::GetCursorPosY() + 10.0f);
            ImGui::TextColored(ImVec4(1.0f, 1.0f, 1.0f, 0.4f), "No results found");
        }
        ImGui::SetCursorPosX((ImGui::GetWindowWidth() - ImGui::CalcTextSize("Clear Search").x) / 2 - 10.0f);
        ImGui::SetCursorPosY(ImGui::GetCursorPosY() + 10.0f);
        UIWidgets::ButtonOptions clearBtnOpts = {};
        clearBtnOpts.size = UIWidgets::Sizes::Inline;
        if (UIWidgets::Button("Clear Search", clearBtnOpts)) {
            menuSearch.Clear();
        }

        ImGui::EndChild();
    } else {
        std::string menuLabel = menuEntries.at(headerIndex).label;
        if (MenuInit::GetUpdateFuncs().contains(menuLabel)) {
            if (MenuInit::GetUpdateFuncs()[menuLabel].contains(sectionIndex)) {
                for (auto& updateFunc : MenuInit::GetUpdateFuncs()[menuLabel][sectionIndex]) {
                    updateFunc();
                }
            }
        }
        for (int i = 0; i < columnFuncs; i++) {
            std::string sectionId = fmt::format("{} Column {}", sectionMenuId, i);
            if (useColumns) {
                ImGui::SetNextWindowSizeConstraints({ columnWidth, 0 }, { columnWidth, columnHeight });
                ImGui::BeginChild(sectionId.c_str(), { columnWidth, windowHeight * 4 },
                                  ImGuiChildFlags_AutoResizeY | ImGuiChildFlags_NavFlattened,
                                  ImGuiWindowFlags_NoTitleBar);
            }
            // for (auto& entryName : sidebar->at(sectionIndex).sidebarOrder) {
            for (auto& entry : sidebar->at(sectionIndex).columnWidgets.at(i)) {
                MenuDrawItem(entry, 90 / sidebar->at(sectionIndex).columnCount, menuThemeIndex);
            }
            //}
            if (useColumns) {
                ImGui::EndChild();
            }
            if (i < columns - 1) {
                ImGui::SameLine();
            }
        }
    }
    if (!useColumns || menuSearchText.length() > 0) {
        ImGui::EndChild();
    }

    if (!popout) {
        ImGui::PopStyleVar();
    }
    ImGui::EndChild();

    // Button hints for controller and TV remote users (hidden after touch/mouse input and under popups).
    if (gamepadNav && GImGui->NavInputSource != ImGuiInputSource_Mouse &&
        !ImGui::IsPopupOpen("", ImGuiPopupFlags_AnyPopupId | ImGuiPopupFlags_AnyPopupLevel)) {
        const char* hint = "A select   B back/close   LB/RB tabs   LT/RT sections\n"
                           "Remote: Play next tab   Rew/FF sections   Back close";
        ImVec2 hintSize = ImGui::CalcTextSize(hint);
        ImVec2 winPos = ImGui::GetWindowPos();
        ImVec2 winSize = ImGui::GetWindowSize();
        float fit = hintSize.x > winSize.x - 40.0f ? (winSize.x - 40.0f) / hintSize.x : 1.0f;
        float fontSize = ImGui::GetFontSize() * fit;
        hintSize = hintSize * fit;
        ImVec2 hintPos = { winPos.x + (winSize.x - hintSize.x) / 2.0f, winPos.y + winSize.y - hintSize.y - 6.0f };
        ImDrawList* drawList = ImGui::GetWindowDrawList();
        drawList->AddRectFilled(hintPos - ImVec2(10, 4), hintPos + hintSize + ImVec2(10, 4), IM_COL32(0, 0, 0, 200),
                                4.0f);
        drawList->AddText(ImGui::GetFont(), fontSize, hintPos, IM_COL32(230, 230, 230, 255), hint);
    }

    if (popout) {
        poppedSize = ImGui::GetWindowSize();
        poppedPos = ImGui::GetWindowPos();
    }
    ImGui::End();
}
} // namespace Ship
