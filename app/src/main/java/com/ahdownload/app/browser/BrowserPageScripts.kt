package com.ahdownload.app.browser

/**
 * Page DOM inspection scripts used by BrowserRoute's render diagnostics.
 *
 * Kept outside the WebView lifecycle implementation so the diagnostic probes can be
 * reviewed independently from navigation and UI state handling.
 */
internal val PAGE_RENDER_SNAPSHOT_SCRIPT = """
                                    (function() {
                                      try {
                                        const d=document, b=d.body, e=d.documentElement;
                                        const text=(b&&b.innerText?b.innerText:'').trim();
                                        const title=d.title||'';
                                        const viewportWidth=window.innerWidth||0, viewportHeight=window.innerHeight||0;
                                        const devicePixelRatio=window.devicePixelRatio||1;
                                        const intersectsViewport=r=>r.width>0&&r.height>0&&r.right>0&&r.bottom>0&&r.left<viewportWidth&&r.top<viewportHeight;
                                        const hasVisibleStyle=(i,r)=>{
                                          const c=getComputedStyle(i);
                                          if(c.display==='none'||c.visibility==='hidden'||Number(c.opacity||1)<=0.05||!intersectsViewport(r)) return false;
                                          for(let p=i.parentElement;p;p=p.parentElement){
                                            if(Number(getComputedStyle(p).opacity||1)<=0.05) return false;
                                          }
                                          return true;
                                        };
                                        const parseRgb = value => {
                                          const m=String(value||'').match(/rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)(?:\s*,\s*([\d.]+))?\s*\)/i);
                                          if(!m) return null;
                                          return {r:Number(m[1]),g:Number(m[2]),b:Number(m[3]),a:m[4]===undefined?1:Number(m[4])};
                                        };
                                        const luminance = color => {
                                          const channel=v=>{v=v/255;return v<=0.04045?v/12.92:Math.pow((v+0.055)/1.055,2.4);};
                                          return 0.2126*channel(color.r)+0.7152*channel(color.g)+0.0722*channel(color.b);
                                        };
                                        const hasReadableContrast = element => {
                                          const foreground=parseRgb(getComputedStyle(element).color);
                                          if(!foreground || foreground.a<0.35) return false;
                                          if(foreground.a<0.98) return true;
                                          let background=null;
                                          for(let p=element;p;p=p.parentElement){
                                            const style=getComputedStyle(p);
                                            if(style.backgroundImage&&style.backgroundImage!=='none') return true;
                                            const candidate=parseRgb(style.backgroundColor);
                                            if(candidate&&candidate.a>=0.95){background=candidate;break;}
                                          }
                                          if(!background) background={r:255,g:255,b:255,a:1};
                                          const a=luminance(foreground), z=luminance(background);
                                          return (Math.max(a,z)+0.05)/(Math.min(a,z)+0.05)>=1.4;
                                        };
                                        const hitTestBlockers = new Map();
                                        const rememberHitTestTarget = target => {
                                          if(!target || !target.tagName) return;
                                          const style=getComputedStyle(target);
                                          const r=target.getBoundingClientRect();
                                          const safeRole=String(target.getAttribute('role')||'').replace(/[^a-zA-Z0-9_-]/g,'').slice(0,16)||'-';
                                          const safeModal=String(target.getAttribute('aria-modal')||'').replace(/[^a-zA-Z0-9_-]/g,'').slice(0,8)||'-';
                                          const descriptor=target.tagName.toLowerCase()+
                                            '[pos='+style.position+
                                            ',z='+style.zIndex+
                                            ',pe='+style.pointerEvents+
                                            ',op='+Number(style.opacity||1).toFixed(2)+
                                            ',size='+Math.round(r.width)+'x'+Math.round(r.height)+
                                            ',role='+safeRole+
                                            ',modal='+safeModal+']';
                                          hitTestBlockers.set(descriptor,true);
                                        };
                                        const hitTestBlockerSummary = () => [...hitTestBlockers.keys()].slice(0,3).join(';').slice(0,220);
                                        const unobscuredAt = (element,rect) => {
                                          const left=Math.max(0,rect.left), right=Math.min(viewportWidth,rect.right);
                                          const topEdge=Math.max(0,rect.top), bottomEdge=Math.min(viewportHeight,rect.bottom);
                                          if(right<=left||bottomEdge<=topEdge) return false;
                                          const width=right-left, height=bottomEdge-topEdge;
                                          const points=[
                                            [left+width*0.5,topEdge+height*0.5],
                                            [left+width*0.2,topEdge+height*0.5],
                                            [left+width*0.8,topEdge+height*0.5],
                                            [left+width*0.5,topEdge+height*0.2],
                                            [left+width*0.5,topEdge+height*0.8]
                                          ];
                                          const rejectedTargets=[];
                                          for(const [x,y] of points) {
                                            const top=d.elementFromPoint(x,y);
                                            if(!top) continue;
                                            if(top===element || element.contains(top)) return true;
                                            if(top!==b && top!==e && top.contains(element)) return true;
                                            rejectedTargets.push(top);
                                          }
                                          rejectedTargets.forEach(rememberHitTestTarget);
                                          return false;
                                        };
                                        let visibleImageCount=0, visibleImageArea=0, visibleLargeImageCount=0;
                                        let viewportIntersectingImageCount=0, hitTestRejectedImageCount=0;
                                        [...d.images].forEach(i=>{
                                          const r=i.getBoundingClientRect();
                                          if(!i.complete||i.naturalWidth<=0||!hasVisibleStyle(i,r)) return;
                                          const width=Math.max(0,Math.min(viewportWidth,r.right)-Math.max(0,r.left));
                                          const height=Math.max(0,Math.min(viewportHeight,r.bottom)-Math.max(0,r.top));
                                          if(width<=0||height<=0) return;
                                          viewportIntersectingImageCount++;
                                          if(!unobscuredAt(i,r)) {
                                            hitTestRejectedImageCount++;
                                            return;
                                          }
                                          visibleImageCount++;
                                          visibleImageArea+=width*height;
                                          if(width>=48&&height>=36) visibleLargeImageCount++;
                                        });
                                        const visibleImageAreaRatio=viewportWidth>0&&viewportHeight>0
                                          ? Math.min(1,visibleImageArea/(viewportWidth*viewportHeight)) : 0;
                                        let visibleTextChars=0, readableVisibleTextChars=0, scannedTextNodes=0;
                                        const visibleTextHostElements=new Set();
                                        let viewportIntersectingTextNodeCount=0, hitTestRejectedTextNodeCount=0;
                                        if(b){
                                          const walker=d.createTreeWalker(b,NodeFilter.SHOW_TEXT);
                                          let node;
                                          while((node=walker.nextNode())&&scannedTextNodes<1500&&visibleTextChars<10000){
                                            scannedTextNodes++;
                                            const value=(node.nodeValue||'').replace(/\s+/g,' ').trim();
                                            if(!value) continue;
                                            const parent=node.parentElement;
                                            if(!parent) continue;
                                            const parentRect=parent.getBoundingClientRect();
                                            if(!hasVisibleStyle(parent,parentRect)) continue;
                                            const range=d.createRange();
                                            range.selectNodeContents(node);
                                            const viewportRects=Array.from(range.getClientRects()).filter(r=>intersectsViewport(r));
                                            if(viewportRects.length===0) continue;
                                            viewportIntersectingTextNodeCount++;
                                            const visibleText=viewportRects.some(r=>unobscuredAt(parent,r));
                                            if(!visibleText) {
                                              hitTestRejectedTextNodeCount++;
                                              continue;
                                            }
                                            visibleTextChars=Math.min(10000,visibleTextChars+value.length);
                                            visibleTextHostElements.add(parent);
                                            if(hasReadableContrast(parent)) readableVisibleTextChars=Math.min(10000,readableVisibleTextChars+value.length);
                                          }
                                        }
                                        const visibleMediaElements=[...d.querySelectorAll('video,canvas,iframe')].filter(i=>{
                                          const r=i.getBoundingClientRect();
                                          if(!hasVisibleStyle(i,r)||r.width<32||r.height<18||!unobscuredAt(i,r)) return false;
                                          if(i.tagName==='VIDEO') return i.videoWidth>0&&i.videoHeight>0&&i.readyState>=2;
                                          if(i.tagName==='CANVAS') return i.width>0&&i.height>0;
                                          return true;
                                        });
                                        const visibleMediaElementCount=visibleMediaElements.length;
                                        const visibleSemanticContainers=[...d.querySelectorAll('main,article,[role="main"]')].filter(i=>{
                                          const r=i.getBoundingClientRect();
                                          if(!hasVisibleStyle(i,r)) return false;
                                          return [...visibleTextHostElements].some(host=>i===host||i.contains(host));
                                        });
                                        const visibleContentElements=new Set([
                                          ...visibleTextHostElements,
                                          ...visibleSemanticContainers,
                                          ...visibleMediaElements
                                        ]);
                                        const challenge=/verify you are human|checking your browser|just a moment|access denied|captcha|unusual traffic|confirm you are not a robot|enable javascript and cookies/i
                                          .test((title+' '+text.slice(0,5000)).toLowerCase());
                                        return JSON.stringify({
                                          readyState:d.readyState||'unknown',
                                          title:title,
                                          bodyTextChars:text.length,
                                          bodyChildCount:b?b.children.length:0,
                                          documentHeight:e?e.scrollHeight:0,
                                          viewportWidth:viewportWidth,
                                          viewportHeight:viewportHeight,
                                          devicePixelRatio:devicePixelRatio,
                                          visibleTextChars:visibleTextChars,
                                          visibleMediaElementCount:visibleMediaElementCount,
                                          videoCount:d.querySelectorAll('video').length,
                                          audioCount:d.querySelectorAll('audio').length,
                                          iframeCount:d.querySelectorAll('iframe').length,
                                          imageCount:d.images.length,
                                          visibleImageCount:visibleImageCount,
                                          viewportIntersectingImageCount:viewportIntersectingImageCount,
                                          hitTestRejectedImageCount:hitTestRejectedImageCount,
                                          visibleImageAreaRatio:visibleImageAreaRatio,
                                          visibleLargeImageCount:visibleLargeImageCount,
                                          viewportIntersectingTextNodeCount:viewportIntersectingTextNodeCount,
                                          hitTestRejectedTextNodeCount:hitTestRejectedTextNodeCount,
                                          hitTestBlockerSummary:hitTestBlockerSummary(),
                                          readableVisibleTextChars:readableVisibleTextChars,
                                          visibleTextHostElementCount:visibleTextHostElements.size,
                                          visibleSemanticContainerCount:visibleSemanticContainers.length,
                                          visibleContentElementCount:visibleContentElements.size,
                                          visibleContentElementScope:'unique_visible_text_hosts_semantic_containers_and_renderable_media',
                                          backgroundColor:b?getComputedStyle(b).backgroundColor:'unknown',
                                          challengeDetected:challenge
                                        });
                                      } catch(e) {
                                        return JSON.stringify({diagnosticError:'dom_snapshot_failed'});
                                      }
                                    })();
                                    """.trimIndent()

internal val PAGE_RENDER_RECHECK_SCRIPT = """
(function() {
  try {
    const d=document,b=d.body,e=d.documentElement;
    const vw=window.innerWidth||0,vh=window.innerHeight||0;
    const intersects=r=>r.width>0&&r.height>0&&r.right>0&&r.bottom>0&&r.left<vw&&r.top<vh;
    const visible=(el,r)=>{
      const style=getComputedStyle(el);
      if(style.display==='none'||style.visibility==='hidden'||Number(style.opacity||1)<=0.05||!intersects(r)) return false;
      for(let p=el.parentElement;p;p=p.parentElement) if(Number(getComputedStyle(p).opacity||1)<=0.05) return false;
      return true;
    };
    const rgb=value=>{
      const m=String(value||'').match(/rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)(?:\s*,\s*([\d.]+))?\s*\)/i);
      return m?{r:Number(m[1]),g:Number(m[2]),b:Number(m[3]),a:m[4]===undefined?1:Number(m[4])}:null;
    };
    const lum=c=>{const f=v=>{v=v/255;return v<=0.04045?v/12.92:Math.pow((v+0.055)/1.055,2.4);};return 0.2126*f(c.r)+0.7152*f(c.g)+0.0722*f(c.b);};
    const readable=el=>{
      const fg=rgb(getComputedStyle(el).color);
      if(!fg||fg.a<0.35) return false;
      if(fg.a<0.98) return true;
      let bg=null;
      for(let p=el;p;p=p.parentElement){
        const s=getComputedStyle(p);
        if(s.backgroundImage&&s.backgroundImage!=='none') return true;
        const c=rgb(s.backgroundColor);
        if(c&&c.a>=0.95){bg=c;break;}
      }
      if(!bg) bg={r:255,g:255,b:255,a:1};
      const a=lum(fg),z=lum(bg);
      return (Math.max(a,z)+0.05)/(Math.min(a,z)+0.05)>=1.4;
    };
        const hitTestBlockers = new Map();
    const rememberHitTestTarget = target => {
      if(!target || !target.tagName) return;
      const style=getComputedStyle(target);
      const rect=target.getBoundingClientRect();
      const safeRole=String(target.getAttribute('role')||'').replace(/[^a-zA-Z0-9_-]/g,'').slice(0,16)||'-';
      const safeModal=String(target.getAttribute('aria-modal')||'').replace(/[^a-zA-Z0-9_-]/g,'').slice(0,8)||'-';
      const descriptor=target.tagName.toLowerCase()+'[pos='+style.position+',z='+style.zIndex+',pe='+style.pointerEvents+',op='+Number(style.opacity||1).toFixed(2)+',size='+Math.round(rect.width)+'x'+Math.round(rect.height)+',role='+safeRole+',modal='+safeModal+']';
      hitTestBlockers.set(descriptor,true);
    };
    const hitTestBlockerSummary = () => [...hitTestBlockers.keys()].slice(0,3).join(';').slice(0,220);
    const unobscured=(el,r)=>{
      const left=Math.max(0,r.left),right=Math.min(vw,r.right),topEdge=Math.max(0,r.top),bottomEdge=Math.min(vh,r.bottom);
      if(right<=left||bottomEdge<=topEdge) return false;
      const width=right-left,height=bottomEdge-topEdge;
      const points=[
        [left+width*0.5,topEdge+height*0.5],
        [left+width*0.2,topEdge+height*0.5],
        [left+width*0.8,topEdge+height*0.5],
        [left+width*0.5,topEdge+height*0.2],
        [left+width*0.5,topEdge+height*0.8]
      ];
      const rejectedTargets=[];
      for(const [x,y] of points) {
        const top=d.elementFromPoint(x,y);
        if(!top) continue;
        if(top===el || el.contains(top)) return true;
        if(top!==b && top!==e && top.contains(el)) return true;
        rejectedTargets.push(top);
      }
      rejectedTargets.forEach(rememberHitTestTarget);
      return false;
    };
    let imageCount=0,imageArea=0,visibleText=0,readableText=0,nodes=0;
    const visibleTextHostElements=new Set();
    let intersectingImages=0,rejectedImages=0,intersectingTextNodes=0,rejectedTextNodes=0;
    const images=[...d.images];
    images.forEach(i=>{
      const r=i.getBoundingClientRect();
      if(!i.complete||i.naturalWidth<=0||!visible(i,r)) return;
      const w=Math.max(0,Math.min(vw,r.right)-Math.max(0,r.left));
      const h=Math.max(0,Math.min(vh,r.bottom)-Math.max(0,r.top));
      if(w<=0||h<=0) return;
      intersectingImages++;
      if(!unobscured(i,r)){rejectedImages++;return;}
      imageCount++;imageArea+=w*h;
    });
    if(b){
      const walker=d.createTreeWalker(b,NodeFilter.SHOW_TEXT);
      let n;
      while((n=walker.nextNode())&&nodes<1000&&visibleText<10000){
        nodes++;
        const value=(n.nodeValue||'').replace(/\s+/g,' ').trim();
        const el=n.parentElement;
        if(!value||!el||!visible(el,el.getBoundingClientRect())) continue;
        const range=d.createRange();range.selectNodeContents(n);
        const rects=[...range.getClientRects()].filter(r=>intersects(r));
        if(rects.length===0) continue;
        intersectingTextNodes++;
        if(!rects.some(r=>unobscured(el,r))){rejectedTextNodes++;continue;}
        visibleText=Math.min(10000,visibleText+value.length);
        visibleTextHostElements.add(el);
        if(readable(el)) readableText=Math.min(10000,readableText+value.length);
      }
    }
    const visibleMediaElements=[...d.querySelectorAll('video,canvas,iframe')].filter(el=>{
      const r=el.getBoundingClientRect();
      if(!visible(el,r)||r.width<32||r.height<18||!unobscured(el,r)) return false;
      if(el.tagName==='VIDEO') return el.videoWidth>0&&el.videoHeight>0&&el.readyState>=2;
      if(el.tagName==='CANVAS') return el.width>0&&el.height>0;
      return true;
    });
    const visibleMedia=visibleMediaElements.length;
    const visibleSemanticContainers=[...d.querySelectorAll('main,article,[role="main"]')].filter(el=>{
      const r=el.getBoundingClientRect();
      if(!visible(el,r)) return false;
      return [...visibleTextHostElements].some(host=>el===host||el.contains(host));
    });
    const visibleContentElements=new Set([
      ...visibleTextHostElements,
      ...visibleSemanticContainers,
      ...visibleMediaElements
    ]);
    const videoCount=d.querySelectorAll('video').length;
    const audioCount=d.querySelectorAll('audio').length;
    const bodyTextChars=(b&&b.innerText?b.innerText:'').trim().length;
    const imageAreaRatio=vw>0&&vh>0?Math.min(1,imageArea/(vw*vh)):0;
    const text=(d.title+' '+(b&&b.innerText?b.innerText:'').slice(0,5000)).toLowerCase();
    const challenge=/verify you are human|checking your browser|just a moment|access denied|captcha|unusual traffic|confirm you are not a robot|enable javascript and cookies/i.test(text);
    return JSON.stringify({
      readyState:d.readyState||'unknown',bodyTextChars:bodyTextChars,bodyChildCount:b?b.children.length:0,
      documentHeight:e?e.scrollHeight:0,videoCount:videoCount,audioCount:audioCount,imageCount:images.length,
      visibleImageCount:imageCount,viewportIntersectingImageCount:intersectingImages,
      hitTestRejectedImageCount:rejectedImages,visibleImageAreaRatio:imageAreaRatio,
      viewportIntersectingTextNodeCount:intersectingTextNodes,hitTestRejectedTextNodeCount:rejectedTextNodes,
      hitTestBlockerSummary:hitTestBlockerSummary(),
      visibleTextChars:visibleText,readableVisibleTextChars:readableText,
      visibleMediaElementCount:visibleMedia,
      visibleTextHostElementCount:visibleTextHostElements.size,
      visibleSemanticContainerCount:visibleSemanticContainers.length,
      visibleContentElementCount:visibleContentElements.size,
      visibleContentElementScope:'unique_visible_text_hosts_semantic_containers_and_renderable_media',
      challengeDetected:challenge
    });
  } catch(e) { return JSON.stringify({diagnosticError:'recheck_failed'}); }
})()
""".trimIndent()
