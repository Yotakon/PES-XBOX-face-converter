import sys,pickle; sys.path.insert(0,'/projects/sandbox/tevez/tools')
import img
S='/projects/sandbox/tevez/CARLOS TEVES UPDATE/tev4/sourceimages/#windx11/'
for n in ['face_bsm_alp','hair_parts_bsm_alp']:
    im=img.load_ftex(S+n+'.ftex'); pickle.dump((im.w,im.h,im.px),open('/projects/sandbox/tevez/work/'+n+'.pkl','wb'))
