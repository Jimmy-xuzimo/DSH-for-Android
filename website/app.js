const toggle=document.querySelector('.theme-button');
let saved;try{saved=localStorage.getItem('dsh-site-theme')}catch{}
const dark=saved?saved==='dark':matchMedia('(prefers-color-scheme: dark)').matches;
document.body.dataset.dark=String(dark);
toggle.addEventListener('click',()=>{const next=document.body.dataset.dark!=='true';document.body.dataset.dark=String(next);try{localStorage.setItem('dsh-site-theme',next?'dark':'light')}catch{}toggle.setAttribute('aria-label',next?'切换为浅色网页':'切换为深色网页')});
