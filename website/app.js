/* Progressive enhancement: content and download links work without JavaScript. */
(() => {
  const root=document.documentElement;
  const motion=matchMedia('(prefers-reduced-motion: reduce)');
  const systemTheme=matchMedia('(prefers-color-scheme: dark)');
  const toggle=document.querySelector('.theme-toggle');
  const header=document.querySelector('.site-header');
  const menu=document.querySelector('.menu-toggle');
  const topButton=document.querySelector('.back-top');
  let explicitTheme=false, switching=false;
  try { explicitTheme=!!localStorage.getItem('dsh-site-theme'); } catch {}
  function syncThemeButton(){
    const dark=root.dataset.theme==='dark';
    toggle.setAttribute('aria-label',dark?'切换到浅色模式':'切换到深色模式');
    toggle.setAttribute('aria-pressed',String(dark));
    document.querySelector('meta[name="theme-color"]').content=dark?'#121418':'#f8f9fb';
  }
  function applyTheme(theme,persist=true){
    root.dataset.theme=theme;
    if(persist){explicitTheme=true;try{localStorage.setItem('dsh-site-theme',theme)}catch{}}
    syncThemeButton();
  }
  syncThemeButton();
  toggle.addEventListener('click',async event=>{
    if(switching)return;
    const next=root.dataset.theme==='dark'?'light':'dark';
    if(motion.matches||!document.startViewTransition){applyTheme(next);return;}
    switching=true;
    const box=toggle.getBoundingClientRect();
    const x=event.clientX||box.left+box.width/2,y=event.clientY||box.top+box.height/2;
    const radius=Math.hypot(Math.max(x,innerWidth-x),Math.max(y,innerHeight-y));
    root.classList.add('theme-transitioning');
    let transition;
    try{
      transition=document.startViewTransition(()=>applyTheme(next));
      await transition.ready;
      const animation=root.animate({clipPath:[`circle(0px at ${x}px ${y}px)`,`circle(${radius}px at ${x}px ${y}px)`]},
        {duration:720,easing:'cubic-bezier(.22,1,.36,1)',pseudoElement:'::view-transition-new(root)'});
      await animation.finished;
      await transition.finished;
    }catch{transition?.skipTransition();applyTheme(next)}
    finally{root.classList.remove('theme-transitioning');switching=false;}
  });
  systemTheme.addEventListener('change',event=>{if(!explicitTheme)applyTheme(event.matches?'dark':'light',false)});

  function closeMenu(){header.classList.remove('menu-open');menu.setAttribute('aria-expanded','false');menu.setAttribute('aria-label','展开导航')}
  menu.addEventListener('click',()=>{const open=!header.classList.contains('menu-open');header.classList.toggle('menu-open',open);menu.setAttribute('aria-expanded',String(open));menu.setAttribute('aria-label',open?'收起导航':'展开导航')});
  document.querySelectorAll('#site-nav a').forEach(link=>link.addEventListener('click',closeMenu));
  document.addEventListener('keydown',event=>{if(event.key==='Escape'&&header.classList.contains('menu-open')){closeMenu();menu.focus()}});
  document.addEventListener('click',event=>{if(!header.contains(event.target))closeMenu()});
  matchMedia('(min-width: 761px)').addEventListener('change',event=>{if(event.matches)closeMenu()});

  const navLinks=[...document.querySelectorAll('#site-nav a')];
  const sections=navLinks.map(a=>document.querySelector(a.getAttribute('href')));
  let scrollFrame=0;
  function updateScroll(){
    scrollFrame=0;
    const distance=root.scrollHeight-innerHeight;
    root.style.setProperty('--scroll-progress',String(distance>0?Math.min(1,Math.max(0,scrollY/distance)):0));
    header.classList.toggle('is-scrolled',scrollY>15);topButton.classList.toggle('is-visible',scrollY>650);
    let current=-1;sections.forEach((section,index)=>{if(section.getBoundingClientRect().top<innerHeight*.38)current=index});
    navLinks.forEach((link,index)=>{if(index===current)link.setAttribute('aria-current','true');else link.removeAttribute('aria-current')});
  }
  addEventListener('scroll',()=>{if(!scrollFrame)scrollFrame=requestAnimationFrame(updateScroll)},{passive:true});
  addEventListener('resize',()=>{if(!scrollFrame)scrollFrame=requestAnimationFrame(updateScroll)},{passive:true});
  updateScroll();topButton.addEventListener('click',()=>scrollTo({top:0,behavior:motion.matches?'instant':'smooth'}));

  document.querySelectorAll('[data-layout]').forEach(button=>button.addEventListener('click',()=>{
    document.querySelector('.layout-stage').dataset.mode=button.dataset.layout;
    document.querySelectorAll('[data-layout]').forEach(other=>other.setAttribute('aria-pressed',String(other===button)));
  }));

  const reveals=[...document.querySelectorAll('[data-reveal]')];
  const counters=[...document.querySelectorAll('[data-count]')];
  const counted=new WeakSet();
  function count(element){
    if(counted.has(element))return;counted.add(element);
    const target=Number(element.dataset.count);if(motion.matches){element.textContent=target;return}
    const started=performance.now();
    const tick=now=>{const progress=Math.min((now-started)/1200,1);element.textContent=Math.round(target*(1-Math.pow(1-progress,3)));if(progress<1&&!motion.matches)requestAnimationFrame(tick);else element.textContent=target};
    requestAnimationFrame(tick);
  }
  if('IntersectionObserver' in window){
    try{
      if(!motion.matches)root.classList.add('motion-ready');
      const revealObserver=new IntersectionObserver(entries=>entries.forEach(entry=>{
        if(!entry.isIntersecting)return;
        entry.target.classList.add('is-revealed');entry.target.querySelectorAll('[data-count]').forEach(count);revealObserver.unobserve(entry.target);
      }),{threshold:.08,rootMargin:'0px 0px -12px 0px'});
      reveals.forEach(element=>revealObserver.observe(element));
    }catch{root.classList.remove('motion-ready')}
  }

  let step=0, demoVisible=false, paused=false, demoTimer;
  const pause=document.querySelector('.demo-pause');
  function updateStep(){
    document.querySelectorAll('[data-step]').forEach(element=>element.classList.toggle('is-active',Number(element.dataset.step)===step));
    document.querySelectorAll('[data-terminal-step]').forEach(element=>element.classList.toggle('is-active',Number(element.dataset.terminalStep)<=step));
  }
  function scheduleDemo(){clearTimeout(demoTimer);if(demoVisible&&!paused&&!motion.matches&&!document.hidden)demoTimer=setTimeout(()=>{step=(step+1)%3;updateStep();scheduleDemo()},2800)}
  updateStep();
  pause.addEventListener('click',()=>{paused=!paused;pause.setAttribute('aria-pressed',String(paused));pause.innerHTML=paused?'继续流程演示 <span>▷</span>':'暂停流程演示 <span>Ⅱ</span>';scheduleDemo()});
  if('IntersectionObserver' in window){
    const liveObserver=new IntersectionObserver(entries=>{for(const entry of entries){entry.target.classList.toggle('is-in-view',entry.isIntersecting);if(entry.target.classList.contains('workflow-grid')){demoVisible=entry.isIntersecting;scheduleDemo()}}},{threshold:.1});
    document.querySelectorAll('.feature-motion,.terminal-card,.models-visual,.workflow-grid').forEach(element=>liveObserver.observe(element));
  }
  document.addEventListener('visibilitychange',scheduleDemo);

  const faqState=new WeakMap();
  document.querySelectorAll('.faq-list details').forEach(details=>{
    const summary=details.querySelector('summary'),content=details.querySelector('.faq-content');
    const state={next:details.open,animation:null};faqState.set(details,state);details.dataset.expanded=String(details.open);
    summary.addEventListener('click',event=>{
      event.preventDefault();state.next=!state.next;
      const current=content.getBoundingClientRect().height;state.animation?.cancel();
      details.dataset.expanded=String(state.next);summary.setAttribute('aria-expanded',String(state.next));
      if(motion.matches||!content.animate){details.open=state.next;content.style.height='';return}
      if(state.next)details.open=true;
      const target=state.next?content.scrollHeight:0;
      content.style.height=`${current}px`;
      const animation=content.animate({height:[`${current}px`,`${target}px`],opacity:[state.next ? 0.2 : 1,state.next?1:0]},
        {duration:Math.min(480,320+target*.25),easing:'cubic-bezier(.22,1,.36,1)'});
      state.animation=animation;
      animation.onfinish=()=>{if(state.animation!==animation)return;details.open=state.next;content.style.height='';state.animation=null};
    });
  });

  const art=document.querySelector('.hero-art');
  let targetX=0,targetY=0,currentX=0,currentY=0,tiltFrame=0;
  function drawTilt(){currentX+=(targetX-currentX)*.09;currentY+=(targetY-currentY)*.09;art.style.setProperty('--tilt-x',`${currentX}deg`);art.style.setProperty('--tilt-y',`${currentY}deg`);if(Math.abs(targetX-currentX)+Math.abs(targetY-currentY)>.015)tiltFrame=requestAnimationFrame(drawTilt);else tiltFrame=0;}
  if(matchMedia('(hover:hover) and (pointer:fine)').matches){
    art.addEventListener('pointermove',event=>{if(motion.matches)return;const b=art.getBoundingClientRect();targetX=((event.clientX-b.left)/b.width-.5)*5;targetY=-((event.clientY-b.top)/b.height-.5)*4;if(!tiltFrame)tiltFrame=requestAnimationFrame(drawTilt)});
    art.addEventListener('pointerleave',()=>{targetX=targetY=0;if(!tiltFrame)tiltFrame=requestAnimationFrame(drawTilt)});
  }
  motion.addEventListener('change',()=>{
    if(motion.matches){root.classList.remove('motion-ready');root.getAnimations({subtree:true}).forEach(animation=>{try{if(animation.effect?.getComputedTiming().iterations===Infinity)animation.cancel();else animation.finish()}catch{animation.cancel()}});counters.forEach(element=>element.textContent=element.dataset.count);targetX=targetY=currentX=currentY=0;art.style.setProperty('--tilt-x','0deg');art.style.setProperty('--tilt-y','0deg');document.querySelectorAll('.faq-list details').forEach(details=>{const state=faqState.get(details);state.animation?.cancel();details.open=state.next;details.querySelector('.faq-content').style.height=''})}
    scheduleDemo();
  });
})();
