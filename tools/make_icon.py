"""產生 App 圖示（方案 B：折角文件，聲波轉成文字行）
輸出 assets/icon-only.png、icon-background.png、icon-foreground.png"""
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
S=1024; K=3; W=S*K
def lerp(a,b,t): return tuple(int(a[i]+(b[i]-a[i])*t) for i in range(len(a)))
PAPER=(250,250,246,255); LINE=(196,210,204,255); TEAL=(24,120,96,255); DARK=(14,74,64,255)
def background():
    c1=(14,74,64); c2=(34,140,110)
    yy,xx=np.mgrid[0:S,0:S]; t=np.clip((xx*0.45+yy*0.55)/S,0,1)[...,None]
    bg=Image.fromarray((np.array(c2)*(1-t)+np.array(c1)*t).astype("uint8"),"RGB")
    hl=Image.new("L",(S,S),0); ImageDraw.Draw(hl).ellipse([-300,-350,700,550],fill=60)
    return Image.composite(Image.new("RGB",(S,S),(120,220,190)),bg,hl.filter(ImageFilter.GaussianBlur(160)))
def foreground(scale):
    im=Image.new("RGBA",(W,W),(0,0,0,0)); u=scale*K; c=W/2
    x0=c-240*u; x1=c+240*u; y0=c-285*u; y1=c+285*u; fold=110*u; r=40*u
    page=[(x0+r,y0),(x1-fold,y0),(x1,y0+fold),(x1,y1-r),(x1-r,y1),(x0+r,y1),(x0,y1-r),(x0,y0+r)]
    sh=Image.new("RGBA",(W,W),(0,0,0,0)); o=20*u
    ImageDraw.Draw(sh).polygon([(p[0],p[1]+o) for p in page],fill=(0,35,28,95))
    im.alpha_composite(sh.filter(ImageFilter.GaussianBlur(26*u)))
    d=ImageDraw.Draw(im); d.polygon(page,fill=PAPER)
    for cx,cy in [(x0+r,y0+r),(x0+r,y1-r),(x1-r,y1-r)]:
        d.ellipse([cx-r,cy-r,cx+r,cy+r],fill=PAPER)
    d.polygon([(x1-fold,y0),(x1-fold,y0+fold-14*u),(x1-14*u,y0+fold)],fill=(214,226,220,255))
    hs=[0.3,0.65,1.0,0.55,0.85,0.4,0.7,0.35]; bw=24*u; gap=20*u; bx=x0+64*u; cy=y0+150*u
    for i,h in enumerate(hs):
        hh=120*u*h; xx=bx+i*(bw+gap)
        d.rounded_rectangle([xx,cy-hh/2,xx+bw,cy+hh/2],radius=bw/2,fill=lerp(TEAL,DARK,i/7))
    for j,wf in enumerate([0.88,0.72,0.88,0.56]):
        ly=y0+265*u+j*72*u
        d.rounded_rectangle([x0+64*u,ly,x0+64*u+(x1-x0-128*u)*wf,ly+24*u],radius=12*u,fill=(120,170,155,255) if j==0 else LINE)
    return im.resize((S,S),Image.LANCZOS)
if __name__=="__main__":
    bg=background(); bg.save("assets/icon-background.png")
    full=bg.convert("RGBA"); full.alpha_composite(foreground(1.0)); full.convert("RGB").save("assets/icon-only.png")
    foreground(0.82).save("assets/icon-foreground.png")
