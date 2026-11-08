"""Independent, newly authored v2 study content; no imported exam questions."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHAPTERS = {
    10: ['دنیای زنده','گوارش و جذب مواد','تبادلات گازی','گردش مواد در بدن','تنظیم اسمزی و دفع مواد زائد','از یاخته تا گیاه','جذب و انتقال مواد در گیاهان'],
    11: ['تنظیم عصبی','حواس','دستگاه حرکتی','تنظیم شیمیایی','ایمنی','تقسیم یاخته','تولید مثل','تولید مثل نهاندانگان','پاسخ گیاهان به محرک‌ها'],
    12: ['مولکول‌های اطلاعاتی','جریان اطلاعات در یاخته','انتقال اطلاعات در نسل‌ها','تغییر در اطلاعات وراثتی','از ماده به انرژی','از انرژی به ماده','فناوری‌های نوین زیستی','رفتارهای جانوران'],
}
ORIGIN = {'kind':'authored','label':'تألیفی مستقل','sourceId':'hezarzist-v2-authored'}
LICENSE = {'id':'project-authored-v2','status':'original_project_content','distributionAllowed':True,'holder':'محتوای مستقل تألیفی پروژه','evidence':'content/v2/LICENSES.md'}
MCQ=[]
FINALS=[]

def mcq(g,c,topic,stem,choices,key,trap,difficulty='medium',image=None,tags=None):
    n=sum(q['grade']==g and q['chapter']==c for q in MCQ)+1
    q={'id':f'V2-MCQ-{g}-{c}-{n:02d}','grade':g,'chapter':c,'topic':topic,'difficulty':difficulty,
       'stem':stem,'options':[x[0] for x in choices],'answer':key,
       'explanation':choices[key][1],'optionExplanations':[x[1] for x in choices],
       'reference':{'sourceId':f'bio{g}','section':f'فصل {c}: {CHAPTERS[g][c-1]}؛ مبحث {topic}'},
       'tags':list(dict.fromkeys([topic]+(tags or []))),'origin':dict(ORIGIN),'license':dict(LICENSE),
       'sourceId':'hezarzist-v2-authored','licenseId':'project-authored-v2',
       'trap':trap,'reviewStatus':'conceptual_review_pending_official_edition_alignment'}
    if image:
        q['image']=image
        q['imageSource']={'sourceId':'hezarzist-v2-authored','licenseId':'project-authored-v2','kind':'original_diagram','caption':f'نمودار آموزشی مستقل پروژه؛ {topic}'}
    MCQ.append(q)

def final(g,c,topic,kind,stem,answer,criteria,truth=None,image=None,imageDescription=None,promptParts=None):
    n=sum(q['grade']==g and q['chapter']==c for q in FINALS)+1
    q={'id':f'V2-FINAL-{g}-{c}-{n:02d}','grade':g,'chapter':c,'topic':topic,'type':kind,
       'stem':stem,'answer':answer,'rubric':[{'id':f'p{i+1}','criterion':v,'points':p} for i,(v,p) in enumerate(criteria)],
       'totalPoints':sum(p for _,p in criteria),'marking':'suggested','rubricLabel':'بارم پیشنهادی؛ خودارزیابی',
       'source':{'kind':'authored','book':f'زیست‌شناسی پایهٔ {g}','section':f'فصل {c}: {CHAPTERS[g][c-1]}؛ {topic}'},
       'origin':dict(ORIGIN),'license':dict(LICENSE),'sourceId':'hezarzist-v2-authored','licenseId':'project-authored-v2',
       'reviewStatus':'conceptual_review_pending_official_edition_alignment'}
    for k,v in [('truth',truth),('image',image),('imageDescription',imageDescription),('promptParts',promptParts)]:
        if v is not None:q[k]=v
    if image:q['imageSource']={'sourceId':'hezarzist-v2-authored','licenseId':'project-authored-v2','kind':'original_diagram','caption':imageDescription or topic}
    FINALS.append(q)
