// Capture clean pixels. Pointer presentation belongs to the renderer.
(() => {
  // Observe the actual pointerdown, after Playwright's actionability waits.
  // Only positions/timing cross the boundary, never typed text or DOM content.
  if (window === window.top && !window.__demoClickListener) {
    window.__demoClickListener = true;
    window.addEventListener('pointerdown', event => {
      if (event.isTrusted && event.button === 0 && window.__demoRecordClick) {
        window.__demoRecordClick({x: event.clientX, y: event.clientY}).catch(() => {});
      }
    }, true);
  }
  const install = () => {
    if (!document.documentElement) return false;
    if (!document.getElementById('__demo_cursor_hidden')) {
      const style = document.createElement('style');
      style.id = '__demo_cursor_hidden';
      style.textContent = '*{cursor:none!important}';
      document.documentElement.append(style);
    }
    return true;
  };
  if (!install()) {
    const observer = new MutationObserver(() => {
      if (install()) observer.disconnect();
    });
    observer.observe(document, {childList: true, subtree: true});
  }
})();
