#!/bin/zsh

# FadCam Build Script with TUI & Progress Bar
# Interactive menu when run without arguments

set -e

# Clear screen immediately
clear

export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"

# ANSI Colors (Red Theme)
RED='\033[0;31m'
DARK_RED='\033[38;5;52m'
BRIGHT_RED='\033[1;31m'
WHITE='\033[1;37m'
GRAY='\033[0;37m'
DIM_GRAY='\033[2;37m'
RESET='\033[0m'
CLEAR='\033c'
BOLD='\033[1m'

# Cursor control
HIDE_CURSOR='\033[?25l'
SHOW_CURSOR='\033[?25h'
SAVE_POS='\033[s'
RESTORE_POS='\033[u'
CLEAR_LINE='\033[2K'
MOVE_UP='\033[1A'

# Progress bar function
progress_bar() {
    local current=$1
    local total=$2
    local width=40
    local percent=$((current * 100 / total))
    local filled=$((current * width / total))
    local empty=$((width - filled))
    
    printf "${RED}["
    printf "${BRIGHT_RED}"
    printf "%${filled}s" | tr ' ' '█'
    printf "${DARK_RED}"
    printf "%${empty}s" | tr ' ' '░'
    printf "${RED}]${RESET} ${percent}%% (${current}/${total})"
}

# TUI Box drawing
draw_box() {
    local width=$1
    local title=$2
    printf "${RED}┌$(printf '─%.0s' {1..$((width-2))})┐${RESET}\n"
    if [ -n "$title" ]; then
        printf "${RED}│${RESET} ${BRIGHT_RED}${BOLD}${title}${RESET}${RED}│${RESET}\n"
        printf "${RED}├$(printf '─%.0s' {1..$((width-2))})┤${RESET}\n"
    fi
}

close_box() {
    local width=$1
    printf "${RED}└$(printf '─%.0s' {1..$((width-2))})┘${RESET}\n"
}

# Print header
print_header() {
    echo -e "${RED}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
    echo -e "${BRIGHT_RED}${BOLD}$1${RESET}"
    echo -e "${RED}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
}

# Print status
print_status() {
    echo -e "$1 $2"
}

# Show help
show_help() {
    clear
    echo ""
    echo -e "${BRIGHT_RED}${BOLD}╔══════════════════════════════════════╗${RESET}"
    echo -e "${BRIGHT_RED}${BOLD}║     🔴 FadCam Build Manager 🔴       ║${RESET}"
    echo -e "${BRIGHT_RED}${BOLD}╚══════════════════════════════════════╝${RESET}"
    echo ""
    echo -e "${BRIGHT_RED}${BOLD}Usage:${RESET}"
    echo -e "${WHITE}  ./build.sh${RESET}${DIM_GRAY}                 # Interactive menu${RESET}"
    echo -e "${WHITE}  ./build.sh debug true${RESET}${DIM_GRAY}      # Build & install debug APK${RESET}"
    echo -e "${WHITE}  ./build.sh release true${RESET}${DIM_GRAY}    # Build & install release APK${RESET}"
    echo -e "${WHITE}  ./build.sh debug false${RESET}${DIM_GRAY}     # Build debug APK only${RESET}"
    echo -e "${WHITE}  ./build.sh release false${RESET}${DIM_GRAY}   # Build release APK only${RESET}"
    echo -e "${WHITE}  ./build.sh --help${RESET}${DIM_GRAY}          # Show this help${RESET}"
    echo ""
    echo -e "${BRIGHT_RED}${BOLD}Menu Options (Interactive Mode):${RESET}"
    echo -e "${BRIGHT_RED}[1]${RESET}  ${WHITE}Build & Install Debug${RESET}${DIM_GRAY} (default)${RESET}"
    echo -e "${BRIGHT_RED}[2]${RESET}  ${WHITE}Build & Install Release${RESET}"
    echo -e "${BRIGHT_RED}[3]${RESET}  ${WHITE}Build Only (Debug)${RESET}"
    echo -e "${BRIGHT_RED}[4]${RESET}  ${WHITE}Build Only (Release)${RESET}"
    echo -e "${BRIGHT_RED}[5]${RESET}  ${WHITE}Clean Build Cache${RESET}"
    echo -e "${BRIGHT_RED}[?]${RESET}  ${WHITE}Show Help${RESET}"
    echo -e "${BRIGHT_RED}[0]${RESET}  ${WHITE}Exit${RESET}"
    echo ""
    echo -e "${BRIGHT_RED}${BOLD}Examples:${RESET}"
    echo -e "${DIM_GRAY}  • Build and test debug: ./build.sh debug true${RESET}"
    echo -e "${DIM_GRAY}  • Build for release: ./build.sh release false${RESET}"
    echo -e "${DIM_GRAY}  • Clean cache first, then build: use menu [5] then [1]${RESET}"
    echo ""
}

# Show interactive menu
show_menu() {
    clear
    echo ""
    echo -e "${BRIGHT_RED}${BOLD}╔══════════════════════════════════════╗${RESET}"
    echo -e "${BRIGHT_RED}${BOLD}║     🔴 FadCam Build Manager 🔴       ║${RESET}"
    echo -e "${BRIGHT_RED}${BOLD}╚══════════════════════════════════════╝${RESET}"
    echo ""
    echo -e "${RED}Available Options:${RESET}"
    echo ""
    echo -e "${BRIGHT_RED}[1]${RESET}  ${WHITE}Build & Install Debug${RESET}${DIM_GRAY} (default)${RESET}"
    echo -e "${BRIGHT_RED}[2]${RESET}  ${WHITE}Build & Install Release${RESET}"
    echo -e "${BRIGHT_RED}[3]${RESET}  ${WHITE}Build Only (Debug)${RESET}"
    echo -e "${BRIGHT_RED}[4]${RESET}  ${WHITE}Build Only (Release)${RESET}"
    echo -e "${BRIGHT_RED}[5]${RESET}  ${WHITE}Clean Build Cache${RESET}"
    echo -e "${BRIGHT_RED}[?]${RESET}  ${WHITE}Help${RESET}"
    echo -e "${BRIGHT_RED}[0]${RESET}  ${WHITE}Exit${RESET}"
    echo ""
    echo -en "${RED}Choose option (default 1): ${RESET}"
}

# Execute build
execute_build() {
    local BUILD_TYPE=$1
    local INSTALL=$2
    local ACTION_NAME=$3
    
    if [ "$BUILD_TYPE" = "debug" ]; then
        GRADLE_TASK="assembleDefaultDebug"
        APK_PATTERN="*debug.apk"
    else
        GRADLE_TASK="assembleDefaultRelease"
        APK_PATTERN="*release.apk"
    fi
    
    print_header "$ACTION_NAME"
    
    echo -e "${RED}Task:${RESET}    ${WHITE}${GRADLE_TASK}${RESET}"
    echo -e "${RED}Install:${RESET}  ${WHITE}${INSTALL}${RESET}"
    echo ""
    
    # Build with real output
    print_status "🔨" "Building APK..."
    echo ""
    
    # Run actual build and capture output
    local BUILD_LOG=$(mktemp)
    local BUILD_START=$(date +%s)
    local LAST_LINE_COUNT=0
    local LAST_PROGRESS_LINE=""
    
    # Start build in background and monitor output
    ./gradlew "$GRADLE_TASK" --no-daemon --warning-mode=summary > "$BUILD_LOG" 2>&1 &
    local BUILD_PID=$!
    
    # Show live output and progress bar while building
    while kill -0 $BUILD_PID 2>/dev/null; do
        sleep 1.5
        # Show last 2 lines of output that haven't been shown yet
        if [ -f "$BUILD_LOG" ]; then
            local current_lines=$(wc -l < "$BUILD_LOG")
            if [ $current_lines -gt $LAST_LINE_COUNT ]; then
                # Show up to 2 new lines
                local start_line=$((LAST_LINE_COUNT + 1))
                local end_line=$current_lines
                local show_lines=$((end_line - start_line + 1))
                
                if [ $show_lines -gt 2 ]; then
                    start_line=$((end_line - 1))
                    show_lines=2
                fi
                
                if [ $show_lines -gt 0 ]; then
                    # Erase previous progress line before printing new output
                    if [ -n "$LAST_PROGRESS_LINE" ]; then
                        printf "\r\033[0K"
                    fi
                    
                    sed -n "${start_line},${end_line}p" "$BUILD_LOG" | while read -r line; do
                        if [ -n "$line" ] && [[ ! "$line" =~ ^[[:space:]]*$ ]]; then
                            echo -e "${DIM_GRAY}  $line${RESET}"
                        fi
                    done
                fi
                
                LAST_LINE_COUNT=$current_lines
            fi
            
            # Show progress bar estimate on same line (without newline)
            local elapsed=$(($(date +%s) - BUILD_START))
            local progress=$((elapsed * 100 / 45))  # Estimate ~45s build
            if [ $progress -gt 95 ]; then progress=95; fi
            
            # Create progress bar with correct character count
            local filled=$((progress / 2))  # 0-50 characters
            local empty=$((50 - filled))
            local bar=$(printf "%-${filled}s" | tr ' ' '█')
            local empty_bar=$(printf "%-${empty}s" | tr ' ' '░')
            
            # Print progress line with carriage return to update same line
            printf "\r${RED}[${BRIGHT_RED}${bar}${DARK_RED}${empty_bar}${RED}]${RESET} ${progress}%% (${progress}/100)\033[0K"
            LAST_PROGRESS_LINE="shown"
        fi
    done
    
    wait $BUILD_PID
    local BUILD_EXIT=$?
    local BUILD_END=$(date +%s)
    local BUILD_TIME=$((BUILD_END - BUILD_START))
    
    # Final progress bar at 100%
    printf "\r${RED}[${BRIGHT_RED}$(printf '%-50s' | tr ' ' '█')${RED}]${RESET} 100%% (100/100)\033[0K\n"
    echo ""
    
    if [ $BUILD_EXIT -eq 0 ]; then
        print_status "✅" "Build completed in ${BUILD_TIME}s"
        echo ""
    else
        print_status "❌" "Build FAILED after ${BUILD_TIME}s"
        echo ""
        echo -e "${RED}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
        echo -e "${BRIGHT_RED}Build Output:${RESET}"
        echo -e "${RED}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
        cat "$BUILD_LOG"
        echo -e "${RED}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
        rm -f "$BUILD_LOG"
        return 1
    fi
    
    rm -f "$BUILD_LOG"
    
    # Find APK
    APK_PATH=$(find app/build/outputs/apk -name "$APK_PATTERN" -type f 2>/dev/null | head -1)
    
    if [ -z "$APK_PATH" ]; then
        print_status "❌" "APK not found in output directory"
        echo -e "${DIM_GRAY}Expected to find: app/build/outputs/apk/**/$APK_PATTERN${RESET}"
        echo ""
        return 1
    fi
    
    APK_DIR=$(dirname "$APK_PATH")
    APK_NAME=$(basename "$APK_PATH")
    APK_SIZE=$(ls -lh "$APK_PATH" | awk '{print $5}')
    
    # Show APK info
    print_header "📦 APK Information"
    print_status "📁" "Directory: ${BRIGHT_RED}${APK_DIR#$(pwd)/}${RESET}"
    print_status "📄" "Filename: ${BRIGHT_RED}${APK_NAME}${RESET}"
    print_status "💾" "Size:     ${BRIGHT_RED}${APK_SIZE}${RESET}"
    echo ""
    
    # Install if requested using Gradle (professional Android approach)
    if [ "$INSTALL" = "true" ]; then
        # Capitalize first letter of BUILD_TYPE for Gradle task (e.g., debug -> Debug)
        local BUILD_TYPE_UPPER=$(echo "$BUILD_TYPE" | tr '[:lower:]' '[:upper:]' | cut -c1)$(echo "$BUILD_TYPE" | cut -c2-)
        
        # Show device info using standard adb approach
        local DEVICE_COUNT=$(adb devices | grep -E '^[a-zA-Z0-9].*[[:space:]]device$' | wc -l)
        if [ $DEVICE_COUNT -eq 0 ]; then
            print_status "⚠️" "No connected devices found"
            echo ""
            return 0
        fi
        
        print_status "📱" "Connected Devices:    ${BRIGHT_RED}${DEVICE_COUNT}${RESET}"
        adb devices | grep -E '^[a-zA-Z0-9].*[[:space:]]device$' | while read -r device _; do
            local DEVICE_MODEL=$(adb -s "$device" shell getprop ro.product.model 2>/dev/null || echo "Unknown")
            local DEVICE_VERSION=$(adb -s "$device" shell getprop ro.build.version.release 2>/dev/null || echo "?")
            echo -e "  ${BRIGHT_RED}[${device:0:16}]${RESET} ${WHITE}${DEVICE_MODEL}${RESET} - ${GRAY}Android ${DEVICE_VERSION}${RESET}"
        done
        echo ""
        
        print_status "📥" "Installing to all connected devices..."
        
        # Use gradle's standard install task (Android official way)
        local INSTALL_TASK="app:installDefault${BUILD_TYPE_UPPER}"
        if ./gradlew "$INSTALL_TASK" --no-daemon 2>&1 | tail -3; then
            print_status "✅" "Installation successful on ${BRIGHT_RED}${DEVICE_COUNT}${RESET} device(s)"
            
            # Launch using Android's official am start command (not gradle run which doesn't exist)
            echo ""
            print_status "🚀" "Launching app on all devices..."
            
            local PACKAGE_NAME="com.fadcam.beta"
            [ "$BUILD_TYPE" = "release" ] && PACKAGE_NAME="com.fadcam"
            
            # Use am start with proper activity path - this is the Android standard
            adb devices | grep -E '^[a-zA-Z0-9].*[[:space:]]device$' | while read -r device _; do
                # Launch the app (Android's official way) - note: full class path needed
                if adb -s "$device" shell am start -W -n "${PACKAGE_NAME}/com.fadcam.SplashActivity" 2>/dev/null; then
                    echo -e "  ${BRIGHT_RED}[${device:0:16}]${RESET} ✅ Launched successfully"
                else
                    echo -e "  ${BRIGHT_RED}[${device:0:16}]${RESET} ⚠️ Launch may have failed"
                fi
            done
        else
            print_status "❌" "Installation failed"
            echo ""
            return 1
        fi
    else
        print_status "💡" "APK ready: ${BRIGHT_RED}${APK_PATH}${RESET}"
    fi
    
    echo ""
    print_header "✨ Build Complete!"
    echo ""
    return 0
}

# Main logic
if [ $# -eq 0 ]; then
    # Interactive menu mode
    while true; do
        show_menu
        read -r choice
        choice=${choice:-1}
        
        case $choice in
            1|"")
                execute_build "debug" "true" "🔴 Debug Build & Install"
                ;;
            2)
                execute_build "release" "true" "🔴 Release Build & Install"
                ;;
            3)
                execute_build "debug" "false" "🔴 Debug Build Only"
                ;;
            4)
                execute_build "release" "false" "🔴 Release Build Only"
                ;;
            5)
                print_header "🔴 Cleaning Build Cache"
                print_status "🧹" "Clearing Gradle cache..."
                ./gradlew clean --quiet --no-daemon
                print_status "✅" "Cache cleaned"
                echo ""
                ;;
            "?"|h|H)
                show_help
                echo -en "${RED}Press Enter to continue...${RESET}"
                read
                ;;
            0)
                echo -e "${RED}Goodbye!${RESET}"
                exit 0
                ;;
            *)
                print_status "❌" "Invalid option. Please try again."
                sleep 2
                ;;
        esac
        
        if [[ "$choice" =~ ^[0-5]$ ]]; then
            if [ $choice -ne 5 ] && [ $choice -ne 0 ]; then
                echo -en "${RED}Press Enter to continue...${RESET}"
                read
            fi
        elif [[ "$choice" == "?" || "$choice" == "h" || "$choice" == "H" ]]; then
            # Help option, continue to next menu iteration
            :
        fi
    done
else
    # Direct execution mode or help
    if [ "$1" = "--help" ] || [ "$1" = "-h" ] || [ "$1" = "help" ]; then
        show_help
    else
        BUILD_TYPE=${1:-debug}
        INSTALL=${2:-true}
        
        if [ "$BUILD_TYPE" = "debug" ]; then
            ACTION_NAME="🔴 Debug Build"
        else
            ACTION_NAME="🔴 Release Build"
        fi
        
        if [ "$INSTALL" = "true" ]; then
            ACTION_NAME="$ACTION_NAME & Install"
        fi
        
        execute_build "$BUILD_TYPE" "$INSTALL" "$ACTION_NAME"
    fi
fi
