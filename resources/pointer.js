(() => {
  const install = () => {
    if (document.getElementById('__demo_pointer')) return;
    const style = document.createElement('style');
    style.textContent = '*{cursor:none!important} #__demo_pointer{position:fixed;left:0;top:0;z-index:2147483647;pointer-events:none;width:25px;height:30px;filter:drop-shadow(1px 2px 2px #3338)} @keyframes demoPulse{from{transform:translate(-50%,-50%) scale(.3);opacity:.8}to{transform:translate(-50%,-50%) scale(2);opacity:0}}';
    document.documentElement.append(style);
    const cursor = document.createElement('div');
    cursor.id = '__demo_pointer';
    cursor.innerHTML = '<svg viewBox="0 0 25 30"><path d="M3 2 L3 24 L9 18 L14 28 L19 25 L14 16 L23 16 Z" fill="white" stroke="#222" stroke-width="2"/></svg>';
    document.documentElement.append(cursor);
    window.__demoMove = (x,y) => {cursor.style.left=x+'px';cursor.style.top=y+'px';};
    window.__demoPulse = (x,y) => {
      const el=document.createElement('div');
      Object.assign(el.style,{position:'fixed',left:x+'px',top:y+'px',width:'36px',height:'36px',border:'3px solid #4285ff',borderRadius:'50%',pointerEvents:'none',zIndex:2147483646,animation:'demoPulse .5s ease-out forwards'});
      document.documentElement.append(el);setTimeout(()=>el.remove(),550);
    };
  };
  install();
})();
