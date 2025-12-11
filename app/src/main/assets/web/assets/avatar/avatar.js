/**
 * Interactive Avatar Component
 * Created by Faded - https://github.com/anonfaded
 * December 9, 2025
 */

class Avatar {
  constructor(parentContainer = null) {
    this.container = null;
    this.parentContainer = parentContainer; // Accept optional parent container
    this.leftEye = null;
    this.rightEye = null;
    this.mouseX = window.innerWidth / 2;
    this.mouseY = window.innerHeight / 2;
    this.currentLeftX = 0;
    this.currentLeftY = 0;
    this.currentRightX = 0;
    this.currentRightY = 0;
    this.targetLeftX = 0;
    this.targetLeftY = 0;
    this.targetRightX = 0;
    this.targetRightY = 0;
    this.blinkInterval = null;
    this.isVisible = true;
    this.animationFrame = null;
    this.avatarCenterX = 0;
    this.avatarCenterY = 0;

    this.init();
  }

  init() {
    this.createAvatarElement();
    this.bindEvents();
    this.startBlinking();
  }

  createAvatarElement() {
    this.container = document.createElement('div');
    this.container.className = 'avatar-container';
    
    this.container.innerHTML = `
      <div class="avatar-wrapper">
        <!-- Avatar Head (Floating) -->
        <div class="avatar-head">
          <!-- Eyes Container -->
          <div class="eyes-container">
            <!-- Left Eye Socket -->
            <div class="eye-socket">
              <div class="eyeball" id="leftEye"></div>
            </div>
            
            <!-- Right Eye Socket -->
            <div class="eye-socket">
              <div class="eyeball" id="rightEye"></div>
            </div>
          </div>
        </div>
        
        <!-- Avatar Body (D-shaped: rounded top, flat bottom) -->
        <div class="avatar-body"></div>
      </div>
    `;

    // Append to parent container if provided, otherwise append to body
    const targetParent = this.parentContainer || document.body;
    targetParent.appendChild(this.container);
    
    // Get eye references
    this.leftEye = document.getElementById('leftEye');
    this.rightEye = document.getElementById('rightEye');
  }

  bindEvents() {
    const handleMouseMove = (e) => {
      this.mouseX = e.clientX;
      this.mouseY = e.clientY;
      this.updateAvatarCenter();
      this.startSmoothAnimation();
    };

    document.addEventListener('mousemove', handleMouseMove, { passive: true });

    // Touch support for mobile devices
    const handleTouchMove = (e) => {
      if (e.touches.length > 0) {
        const touch = e.touches[0];
        this.mouseX = touch.clientX;
        this.mouseY = touch.clientY;
        this.updateAvatarCenter();
        this.startSmoothAnimation();
      }
    };

    document.addEventListener('touchmove', handleTouchMove, { passive: true });

    // Visibility on scroll (fade when scrolling)
    let scrollTimeout;
    window.addEventListener('scroll', () => {
      if (this.container) {
        this.container.style.opacity = '0.7';
        clearTimeout(scrollTimeout);
        scrollTimeout = window.setTimeout(() => {
          if (this.container) {
            this.container.style.opacity = '1';
          }
        }, 150);
      }
    }, { passive: true });

    // Initialize eyes with current mouse position
    this.updateAvatarCenter();
    this.startSmoothAnimation();
  }

  updateAvatarCenter() {
    if (!this.container) return;
    
    const containerRect = this.container.getBoundingClientRect();
    this.avatarCenterX = containerRect.left + containerRect.width / 2;
    this.avatarCenterY = containerRect.top + containerRect.height / 2;
  }

  startSmoothAnimation() {
    if (this.animationFrame) {
      cancelAnimationFrame(this.animationFrame);
    }
    this.animationFrame = requestAnimationFrame(() => this.animate());
  }

  animate() {
    this.updateTargetPositions();
    this.interpolateEyePositions();
    this.updateEyeColors();
    this.applyEyeTransforms();
    
    // Continue animation loop
    this.animationFrame = requestAnimationFrame(() => this.animate());
  }

  updateTargetPositions() {
    if (!this.leftEye || !this.rightEye) return;

    const leftSocket = this.leftEye.parentElement;
    const rightSocket = this.rightEye.parentElement;
    
    const leftRect = leftSocket.getBoundingClientRect();
    const rightRect = rightSocket.getBoundingClientRect();
    
    // Calculate center of each eye socket
    const leftCenterX = leftRect.left + leftRect.width / 2;
    const leftCenterY = leftRect.top + leftRect.height / 2;
    const rightCenterX = rightRect.left + rightRect.width / 2;
    const rightCenterY = rightRect.top + rightRect.height / 2;
    
    // Left eye calculation
    const leftAngle = Math.atan2(
      this.mouseY - leftCenterY,
      this.mouseX - leftCenterX
    );
    const leftDistance = Math.min(
      leftRect.width / 4,
      Math.sqrt(
        Math.pow(this.mouseX - leftCenterX, 2) + Math.pow(this.mouseY - leftCenterY, 2)
      )
    );
    this.targetLeftX = leftDistance * Math.cos(leftAngle);
    this.targetLeftY = leftDistance * Math.sin(leftAngle);
    
    // Right eye calculation
    const rightAngle = Math.atan2(
      this.mouseY - rightCenterY,
      this.mouseX - rightCenterX
    );
    const rightDistance = Math.min(
      rightRect.width / 4,
      Math.sqrt(
        Math.pow(this.mouseX - rightCenterX, 2) + Math.pow(this.mouseY - rightCenterY, 2)
      )
    );
    this.targetRightX = rightDistance * Math.cos(rightAngle);
    this.targetRightY = rightDistance * Math.sin(rightAngle);
  }

  interpolateEyePositions() {
    const lerpFactor = 0.12; // Smooth interpolation factor
    
    // Smooth interpolation toward target positions
    this.currentLeftX += (this.targetLeftX - this.currentLeftX) * lerpFactor;
    this.currentLeftY += (this.targetLeftY - this.currentLeftY) * lerpFactor;
    this.currentRightX += (this.targetRightX - this.currentRightX) * lerpFactor;
    this.currentRightY += (this.targetRightY - this.currentRightY) * lerpFactor;
  }

  updateEyeColors() {
    if (!this.leftEye || !this.rightEye) return;

    // Calculate distance from mouse to avatar center
    const distance = Math.sqrt(
      Math.pow(this.mouseX - this.avatarCenterX, 2) + 
      Math.pow(this.mouseY - this.avatarCenterY, 2)
    );

    // Define distance thresholds (pixels)
    const minDistance = 100; // Distance where eyes are fully white (near)
    const maxDistance = 500; // Distance where eyes are fully red (far)

    // Calculate color intensity (0 = white when near, 1 = red when far)
    let intensity = 0;
    if (distance > minDistance) {
      intensity = Math.min((distance - minDistance) / (maxDistance - minDistance), 1);
    }

    // Interpolate: white (near) → red (far)
    const red = Math.round(255);
    const green = Math.round(255 * (1 - intensity));
    const blue = Math.round(255 * (1 - intensity));

    const color = `rgb(${red}, ${green}, ${blue})`;
    const glowIntensity = intensity;

    // Create dynamic shadow based on color
    const whiteShadow = `0 0 8px rgba(255,255,255,${0.8 * (1 - glowIntensity)}), 0 0 16px rgba(255,255,255,${0.4 * (1 - glowIntensity)})`;
    const redShadow = `0 0 8px rgba(255,0,0,${0.8 * glowIntensity}), 0 0 16px rgba(255,0,0,${0.6 * glowIntensity}), 0 0 24px rgba(255,0,0,${0.4 * glowIntensity})`;
    const combinedShadow = glowIntensity > 0 ? `${whiteShadow}, ${redShadow}` : whiteShadow;

    // Apply color and glow to both eyes
    this.leftEye.style.backgroundColor = color;
    this.leftEye.style.boxShadow = combinedShadow;
    this.rightEye.style.backgroundColor = color;
    this.rightEye.style.boxShadow = combinedShadow;
  }

  applyEyeTransforms() {
    if (!this.leftEye || !this.rightEye) return;
    
    // Apply smooth transforms
    this.leftEye.style.transform = `translate(-50%, -50%) translate(${this.currentLeftX.toFixed(2)}px, ${this.currentLeftY.toFixed(2)}px)`;
    this.rightEye.style.transform = `translate(-50%, -50%) translate(${this.currentRightX.toFixed(2)}px, ${this.currentRightY.toFixed(2)}px)`;
  }

  startBlinking() {
    const blink = () => {
      const eyeSockets = this.container?.querySelectorAll('.eye-socket');
      if (!eyeSockets) return;

      eyeSockets.forEach((socket) => {
        const originalHeight = '16px'; // h-4 equivalent
        
        // Smooth blink animation with easing
        socket.style.transition = 'height 0.12s cubic-bezier(0.4, 0, 0.2, 1)';
        socket.style.height = '1px';
        
        setTimeout(() => {
          socket.style.height = originalHeight;
          setTimeout(() => {
            socket.style.transition = '';
          }, 120);
        }, 120);
      });
      
      // Random blink interval (3-7 seconds)
      const nextBlink = 3000 + Math.random() * 4000;
      this.blinkInterval = window.setTimeout(blink, nextBlink);
    };

    // Start blinking
    blink();
  }

  /**
   * Show/hide avatar
   */
  toggle(visible) {
    if (!this.container) return;
    
    this.isVisible = visible !== undefined ? visible : !this.isVisible;
    this.container.style.transform = this.isVisible 
      ? 'translateY(0)' 
      : 'translateY(100%)';
  }

  /**
   * Update avatar position
   */
  setPosition(position) {
    if (!this.container) return;

    // Remove existing position classes
    this.container.classList.remove('pos-bottom-right', 'pos-bottom-left', 'pos-top-right', 'pos-top-left');

    switch (position) {
      case 'bottom-right':
        this.container.classList.add('pos-bottom-right');
        break;
      case 'bottom-left':
        this.container.classList.add('pos-bottom-left');
        break;
      case 'top-right':
        this.container.classList.add('pos-top-right');
        break;
      case 'top-left':
        this.container.classList.add('pos-top-left');
        break;
    }
  }

  /**
   * Enable debug mode to visualize eye tracking
   */
  enableDebugMode() {
    // Create debug overlay
    const debugOverlay = document.createElement('div');
    debugOverlay.id = 'avatar-debug';
    debugOverlay.style.cssText = `
      position: fixed;
      top: 10px;
      left: 10px;
      background: rgba(0, 0, 0, 0.8);
      color: white;
      padding: 10px;
      font-family: monospace;
      font-size: 12px;
      z-index: 10000;
      border-radius: 5px;
      pointer-events: none;
    `;
    document.body.appendChild(debugOverlay);

    // Create mouse cursor indicator
    const cursorIndicator = document.createElement('div');
    cursorIndicator.id = 'cursor-indicator';
    cursorIndicator.style.cssText = `
      position: fixed;
      width: 10px;
      height: 10px;
      background: red;
      border-radius: 50%;
      pointer-events: none;
      z-index: 10001;
      transform: translate(-50%, -50%);
    `;
    document.body.appendChild(cursorIndicator);

    // Update debug info and cursor position
    const updateDebug = () => {
      if (!this.leftEye || !this.rightEye) return;

      const leftSocket = this.leftEye.parentElement;
      const leftRect = leftSocket.getBoundingClientRect();
      const leftCenterX = leftRect.left + leftRect.width / 2;
      const leftCenterY = leftRect.top + leftRect.height / 2;

      debugOverlay.innerHTML = `
        Mouse: (${this.mouseX}, ${this.mouseY})<br>
        Left Eye Center: (${leftCenterX.toFixed(0)}, ${leftCenterY.toFixed(0)})<br>
        Avatar Center: (${this.avatarCenterX.toFixed(0)}, ${this.avatarCenterY.toFixed(0)})<br>
        Distance: ${Math.sqrt(Math.pow(this.mouseX - this.avatarCenterX, 2) + Math.pow(this.mouseY - this.avatarCenterY, 2)).toFixed(0)}px<br>
        Viewport: ${window.innerWidth} x ${window.innerHeight}<br>
        Left Transform: ${this.leftEye.style.transform}
      `;

      cursorIndicator.style.left = this.mouseX + 'px';
      cursorIndicator.style.top = this.mouseY + 'px';
    };

    // Update debug info on mouse move
    document.addEventListener('mousemove', updateDebug);
  }

  /**
   * Cleanup
   */
  destroy() {
    if (this.blinkInterval) {
      clearTimeout(this.blinkInterval);
    }
    
    if (this.animationFrame) {
      cancelAnimationFrame(this.animationFrame);
    }
    
    if (this.container && this.container.parentNode) {
      this.container.parentNode.removeChild(this.container);
    }
  }
}
