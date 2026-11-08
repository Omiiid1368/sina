"""Spatial NOTRO PDF extraction. No OCR, biology guesses or official provenance claims.
parse_pdf(inventory_entry) -> {questions,keyMap,keyPage,stats,issues}; crop_record(...).
"""
from __future__ import annotations
import collections,hashlib,json,re,unicodedata
from pathlib import Path
import fitz
from PIL import Image

def norm(s):
 s=unicodedata.normalize('NFKC',s).replace('ي','ی').replace('ك','ک').replace('\u200e','').replace('\u200f','')
 return s.translate(str.maketrans('۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩','01234567890123456789'))
def clean(s):return re.sub(r'[ \t]+',' ',norm(s)).strip()
def _spans(page):
 return [{**s,'text':norm(s['text'])} for b in page.get_text('dict')['blocks'] for line in b.get('lines',[]) for s in line['spans']]
def _chars(page):
 return [dict(text=norm(''.join(c['c'] for c in s['chars'])),font=s['font'],size=s['size'],chars=[dict(text=norm(c['c']),bbox=c['bbox']) for c in s['chars']],bbox=s['bbox']) for b in page.get_text('rawdict')['blocks'] for line in b.get('lines',[]) for s in line['spans']]
def region_lines(page,rect,mode='all',omit=None):
 """Keep character-level spatial intersections: spans can cross two option columns."""
 x0,y0,x1,y1=rect; fragments=[]
 for s in _chars(page):
  size=s['size']; font=s['font']
  if mode=='stem' and abs(size-7.9)<.2:continue
  if mode=='option' and not (8.7<=size<=9.65 or ('BKoodak' not in font and 4.5<=size<=9.8) or 4.5<=size<=7.0):continue
  if mode=='solution' and abs(size-7.9)<.15:continue
  if omit and omit(s):continue
  cc=[c for c in s['chars'] if x0<= (c['bbox'][0]+c['bbox'][2])/2 <x1 and y0<=(c['bbox'][1]+c['bbox'][3])/2<y1]
  if not cc:continue
  text=''.join(c['text'] for c in cc)
  if not text.strip():continue
  visible=[c for c in cc if c['text'].strip()]
  bbox=[min(c['bbox'][0] for c in visible),min(c['bbox'][1] for c in visible),max(c['bbox'][2] for c in visible),max(c['bbox'][3] for c in visible)]
  # Char positions sometimes assign one whitespace span hundreds of points; never order by that span.
  fragments.append(dict(text=text,bbox=bbox,font=font,size=size))
 groups=[]
 for s in sorted(fragments,key=lambda x:(x['bbox'][1],-x['bbox'][2])):
  cy=(s['bbox'][1]+s['bbox'][3])/2
  near=[g for g in groups if abs(g['cy']-cy)<=4.7]
  if near:min(near,key=lambda g:abs(g['cy']-cy))['spans'].append(s)
  else:groups.append(dict(cy=cy,spans=[s]))
 lines=[]
 for g in sorted(groups,key=lambda x:x['cy']):
  spans=sorted(g['spans'],key=lambda s:-s['bbox'][2])
  text=clean(' '.join(s['text'] for s in spans))
  box=[min(s['bbox'][0] for s in spans),min(s['bbox'][1] for s in spans),max(s['bbox'][2] for s in spans),max(s['bbox'][3] for s in spans)]
  lines.append(dict(text=text,rect=box,spans=spans))
 return lines
def region_text(page,rect,mode='all',omit=None):return '\n'.join(l['text'] for l in region_lines(page,rect,mode,omit))
def _segments(start,end,doc):
 p0,y0=start;p1,y1=end
 return [dict(page=p+1,rect=[44,y0 if p==p0 else 46,555,y1 if p==p1 else 789]) for p in range(p0,p1+1) if (y1 if p==p1 else 789)>(y0 if p==p0 else 46)+2]
def _key_table(doc,n):
 candidates=[]
 for pi in range(len(doc)-1,max(-1,len(doc)-4),-1):
  spans=[s for s in _spans(doc[pi]) if s['bbox'][1]<790 and s['text'].strip().isdigit() and 7.5<=s['size']<=10]
  nums=[int(s['text'].strip()) for s in spans]
  if len(nums)%2:continue
  pairs=list(zip(nums[::2],nums[1::2]))
  if len(pairs)>3 and [q for q,a in pairs]==list(range(1,len(pairs)+1)) and all(a in (1,2,3,4) for q,a in pairs):
   candidates.append((pi,{q:a-1 for q,a in pairs}))
 for pi,k in candidates:
  if len(k)==n:return pi,k
 return candidates[0] if candidates else (None,{})
def _bubbles(page,rect):
 out=[]
 for d in page.get_drawings():
  r=d['rect'];f=d.get('fill')
  if f and max(abs(f[i]-[.78,.098,.49][i]) for i in range(3))<.03 and 25<r.width<32 and 13<r.height<19 and rect[1]<=r.y0 and r.y1<=rect[3]+4:
   out.append(tuple(r))
 return out
def parse_pdf(entry):
 doc=fitz.open(entry['path']); markers=[];solution_headers=[];issues=[]
 page_spans=[_spans(p) for p in doc]
 for pi,ss in enumerate(page_spans):
  for s in ss:
   t=s['text'].strip();x0,y0,x1,y1=s['bbox']
   if t.isdigit() and x0>=527 and x1<547 and y0<790 and 9.8<=s['size']<=11:
    markers.append(dict(number=int(t),page=pi+1,y=y0,bbox=list(s['bbox'])))
   if t=='گزینه' and 495<=x0<=505 and 7.9<=s['size']<=8.6:
    same=[a for a in ss if a['text'].strip().isdigit() and abs(a['bbox'][1]-y0)<2 and a['bbox'][0]>531 and a['bbox'][2]<548]
    ans=[a for a in ss if a['text'].strip() in ('1','2','3','4') and 490<=a['bbox'][2]<=505 and abs(a['bbox'][1]-y0)<3]
    if len(same)==1:solution_headers.append(dict(number=int(same[0]['text']),page=pi+1,y=min(y0,same[0]['bbox'][1]),answer=int(ans[0]['text'])-1 if len(ans)==1 else None))
 markers.sort(key=lambda x:(x['page'],x['y']));solution_headers.sort(key=lambda x:(x['page'],x['y']))
 raw_markers=markers;markers=[];expected=1;ignored=[]
 for marker in raw_markers:
  if marker['number']==expected:markers.append(marker);expected+=1
  elif marker['number']<expected:ignored.append(marker)
  else:markers.append(marker);expected=marker['number']+1
 if ignored:issues.append(dict(kind='ignored_nonsequential_margin_numbers',markers=ignored))
 # A pure right-margin 10.5pt number is the question marker, unlike 8.2pt solution numbers.
 n=max((m['number'] for m in markers),default=0)
 if [m['number'] for m in markers]!=list(range(1,n+1)):issues.append(dict(kind='question_sequence',numbers=[m['number'] for m in markers]))
 key_pi,key=_key_table(doc,n)
 if len(key)!=n:issues.append(dict(kind='key_count',expected=n,found=len(key),page=None if key_pi is None else key_pi+1))
 solmap={s['number']:s for s in solution_headers}
 if sorted(solmap)!=list(range(1,n+1)):issues.append(dict(kind='solution_sequence',expected=n,numbers=sorted(solmap)))
 first_solution=solution_headers[0] if solution_headers else None
 results=[];topic=''
 for i,m in enumerate(markers):
  pi=m['page']-1
  nextm=markers[i+1] if i+1<len(markers) else first_solution
  end=(nextm['page']-1,nextm['y']-2) if nextm else (pi,789)
  segments=_segments((pi,m['y']-1),end,doc)
  if i+1==len(markers) and first_solution and first_solution['page']>m['page']:
   # Front matter of the solution section is not part of the last question.
   segments=[s for s in segments if s['page']<=m['page'] or any(_bubbles(doc[s['page']-1],s['rect']))]
  bubbles=[]
  for seg in segments:
   for r in _bubbles(doc[seg['page']-1],seg['rect']):bubbles.append(dict(page=seg['page'],rect=list(r)))
  # Cluster rows before sorting: floating y coordinate differs slightly for vector circles.
  grouped=[]
  for b in sorted(bubbles,key=lambda z:(z['page'],z['rect'][1])):
   row=next((r for r in grouped if r['page']==b['page'] and abs(r['y']-b['rect'][1])<4),None)
   if row:row['bubbles'].append(b)
   else:grouped.append(dict(page=b['page'],y=b['rect'][1],bubbles=[b]))
  ordered=[b for row in grouped for b in sorted(row['bubbles'],key=lambda z:-z['rect'][0])]
  # In two-column layouts, a vector label may sit above its text. Pair by
  # column first so a raised label 4 is not mistaken for option 3.
  columns=[]
  for b in bubbles:
   col=next((c for c in columns if abs(c['x']-b['rect'][0])<4),None)
   if col:col['bubbles'].append(b)
   else:columns.append(dict(x=b['rect'][0],bubbles=[b]))
  opts=[];option_regions=[]
  if len(bubbles)==4 and len(columns)==2 and all(len(c['bubbles'])==2 for c in columns) and len({b['page'] for b in bubbles})==1:
   columns.sort(key=lambda c:-c['x'])
   for c in columns:c['bubbles'].sort(key=lambda b:b['rect'][1])
   ordered=[columns[0]['bubbles'][0],columns[1]['bubbles'][0],columns[0]['bubbles'][1],columns[1]['bubbles'][1]]
   for idx,b in enumerate(ordered):
    ci=idx%2;ri=idx//2;col=columns[ci];seg=next(v for v in segments if v['page']==b['page'])
    left=columns[1]['bubbles'][ri]['rect'][2]+.5 if ci==0 else 44
    lower=col['bubbles'][ri+1]['rect'][1]-1 if ri==0 else seg['rect'][3]
    rect=[left,b['rect'][1]-4,b['rect'][0]+.4,lower]
    opts.append(region_text(doc[b['page']-1],rect,'option'));option_regions.append(dict(page=b['page'],rect=rect))
  for rowi,row in enumerate(grouped if not opts else []):
   bs=sorted(row['bubbles'],key=lambda b:-b['rect'][0])
   seg=next(s for s in segments if s['page']==row['page'])
   lower=next((r['y']-1 for r in grouped[rowi+1:] if r['page']==row['page']),seg['rect'][3])
   for bi,b in enumerate(bs):
    right=b['rect'][0]+.4;left=bs[bi+1]['rect'][2]+.5 if bi+1<len(bs) else 44
    rect=[left,row['y']-4,right,lower]
    opts.append(region_text(doc[row['page']-1],rect,'option'))
    option_regions.append(dict(page=row['page'],rect=rect))
  if ordered:
   first=ordered[0];stem_segments=[]
   for seg in segments:
    if seg['page']<first['page']:stem_segments.append(seg)
    elif seg['page']==first['page']:stem_segments.append(dict(page=seg['page'],rect=[44,seg['rect'][1],555,first['rect'][1]-3]))
   stem='\n'.join(region_text(doc[s['page']-1],s['rect'],'stem',lambda sp:sp['bbox'][0]>=527 and sp['text'].strip()==str(m['number'])) for s in stem_segments)
  else:stem=region_text(doc[pi],segments[0]['rect'],'stem') if segments else ''
  qissues=[]
  if len(opts)!=4 or not all(opts):qissues.append(dict(kind='option_extraction',optionCount=len(opts),empty=[j+1 for j,o in enumerate(opts) if not o]))
  if not stem.strip():qissues.append(dict(kind='empty_stem'))
  sources=[s for s in page_spans[pi] if abs(s['size']-7.9)<.2 and abs(s['bbox'][1]-m['y'])<29 and s['bbox'][0]<135]
  sourceclaim=region_text(doc[pi],[44,m['y']-3,140,m['y']+30],'all') if sources else ''
  # Keep only source-size fragments to exclude left-hand question/option text.
  sourceclaim=' '.join(s['text'] for s in sorted(sources,key=lambda s:(s['bbox'][1],s['bbox'][0])))
  headings=[s for s in page_spans[pi] if s['size']>=11.5 and s['bbox'][1]<m['y'] and s['bbox'][1]>46 and not s['text'].strip().isdigit()]
  if headings:topic=clean(' '.join(s['text'] for s in sorted(headings,key=lambda s:(s['bbox'][1],-s['bbox'][2]))[-5:]))
  sh=solmap.get(m['number']);solsegments=[];sollines=[]
  if sh:
   idx=solution_headers.index(sh); snext=solution_headers[idx+1] if idx+1<len(solution_headers) else None
   send=(snext['page']-1,snext['y']-1) if snext else ((key_pi-1,789) if key_pi is not None else (len(doc)-1,789))
   solsegments=_segments((sh['page']-1,sh['y']-1),send,doc)
   for seg in solsegments:
    pp=doc[seg['page']-1]
    def omit(sp):return seg['page']==sh['page'] and abs(sp['bbox'][1]-sh['y'])<3 and sp['bbox'][0]>=490
    for line in region_lines(pp,seg['rect'],'solution',omit):sollines.append(dict(page=seg['page'],**line))
   if m['number'] in key and sh['answer'] is not None and sh['answer']!=key[m['number']]:qissues.append(dict(kind='key_solution_disagreement',key=key[m['number']],solution=sh['answer']))
  else:qissues.append(dict(kind='missing_solution_header'))
  results.append(dict(number=m['number'],page=m['page'],stem=clean(stem),options=[clean(o) for o in opts],sourceClaim=clean(sourceclaim),topic=topic,segments=segments,optionRegions=option_regions,answer=key.get(m['number']),solution='\n'.join(l['text'] for l in sollines),solutionLines=sollines,solutionSegments=solsegments,solutionHeaderAnswer=sh['answer'] if sh else None,issues=qissues))
 return dict(questions=results,keyMap=key,keyPage=None if key_pi is None else key_pi+1,stats=dict(questionCount=n,extractedCount=len(results),keyCount=len(key),solutionCount=len(solmap),questionPages=sorted(set(m['page'] for m in markers))),issues=issues)
def crop_record(entry,segments,outfile,scale=2.4):
 """Faithful full question/solution composite preserves diagrams, glyphs and formula order."""
 doc=fitz.open(entry['path']);images=[]
 for s in segments:
  r=fitz.Rect(s['rect']);r.x0=max(40,r.x0-3);r.x1=min(558,r.x1+3);r.y0=max(43,r.y0-3);r.y1=min(790,r.y1+1)
  if r.height<=0:continue
  pix=doc[s['page']-1].get_pixmap(matrix=fitz.Matrix(scale,scale),clip=r,alpha=False)
  images.append(Image.frombytes('RGB',[pix.width,pix.height],pix.samples))
 if not images:raise ValueError('No image segments')
 width=max(i.width for i in images);height=sum(i.height for i in images)+12*(len(images)-1)
 canvas=Image.new('RGB',(width,height),'white');y=0
 for im in images:canvas.paste(im,(width-im.width,y));y+=im.height+12
 if height>7600:canvas.thumbnail((1600,7600))
 Path(outfile).parent.mkdir(parents=True,exist_ok=True);canvas.save(outfile,optimize=True)
 return dict(width=canvas.width,height=canvas.height,sha256=hashlib.sha256(Path(outfile).read_bytes()).hexdigest())
