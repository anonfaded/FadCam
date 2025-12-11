/**
 * FadCam Web Dashboard
 * Main application bootstrap
 */

// Global instances
let dashboardViewModel = null;

// Initialize on DOM ready
document.addEventListener('DOMContentLoaded', async () => {
    console.log('[App] Initializing FadCam Web Dashboard...');
    
    // Initialize ViewModel
    dashboardViewModel = new DashboardViewModel();
    await dashboardViewModel.initialize();
    
    // Initialize Components
    statusCard = new StatusCard(document.getElementById('statusCard'));
    systemMetrics = new SystemMetrics(document.getElementById('systemMetrics'));
    playerCard = new PlayerCard(document.getElementById('playerCard'));
    controlPanel = new ControlPanel(document.getElementById('controlPanel'));
    controlPanel.setViewModel(dashboardViewModel);
    
    console.log('[App] Dashboard initialized successfully');
});

// Cleanup on unload
window.addEventListener('beforeunload', () => {
    if (dashboardViewModel) {
        dashboardViewModel.destroy();
    }
    if (hlsService) {
        hlsService.destroy();
    }
});
