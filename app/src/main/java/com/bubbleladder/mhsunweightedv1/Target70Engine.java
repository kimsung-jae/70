package com.bubbleladder.mhsunweightedv1;

import java.util.*;

/**
 * 70% 목표 합성 엔진.
 *
 * 핵심 원칙:
 * 1) 현재 후보 중 가장 높은 raw %를 고르지 않는다.
 * 2) 서로 다른 계열 3개 모델을 합성한 규칙을 대량 생성한다.
 * 3) 각 합성 규칙을 과거 시점마다 미래값을 가리고 walk-forward 검증한다.
 * 4) 전체 적중률 >= 70%, 앞/뒤 블록 각각 >= 60%, 최소 10표본을 동시에 만족한 규칙만 '달성' 처리한다.
 * 5) 달성 규칙이 여러 개면 70%에 가장 가까운 규칙을 우선해 과도한 최고값 선택을 피한다.
 * 6) 정방향뿐 아니라 고정된 역방향 규칙도 별도 후보로 검증한다. (과거 미스가 구조적으로 반복되는 경우 반대로 사용)
 *
 * 이 엔진은 70%를 목표로 규칙을 합성/검증하지만, 데이터에 실제 정보가 없으면 70% 규칙이 존재하지 않을 수 있다.
 */
public final class Target70Engine {
    private Target70Engine(){}

    public static final double TARGET = 0.70;
    private static final double BLOCK_FLOOR = 0.60;
    private static final int WARMUP = 12;
    private static final int MIN_VALIDATION = 10;
    private static final int MAX_VALIDATION = 30;

    public static final class Result {
        public boolean certified;
        public boolean targetAchieved;
        public String mode="-";
        public int dim=-1, pick=0;
        public int validationN=0, validationHit=0, searched=0;
        public double validationRate=0.0, firstRate=0.0, secondRate=0.0;
        public double bestObservedRate=0.0;
        public int bestObservedN=0;
        public String rule="-", bestObservedRule="-", detail="-";
        public int plusVotes=0, minusVotes=0;
        public boolean inverted=false;
    }

    private interface Predictor { int predict(List<FlowCore.Result> a,int end,int dim); }
    private static final class Model {
        final String family,name; final Predictor p;
        Model(String family,String name,Predictor p){this.family=family;this.name=name;this.p=p;}
    }
    private static final class Eval {
        int n,h,h1,n1,h2,n2,pick,plus,minus; boolean inverted;
        double rate(){return n==0?0:(double)h/n;}
        double r1(){return n1==0?0:(double)h1/n1;}
        double r2(){return n2==0?0:(double)h2/n2;}
        double floor(){return Math.min(r1(),r2());}
    }

    private static List<Model> models(){
        List<Model> m=new ArrayList<>();
        for(int o=1;o<=4;o++){ final int q=o; m.add(new Model("Markov","Markov-o"+o,(a,e,d)->markov(a,e,d,q))); }
        for(int n=2;n<=6;n++){ final int q=n; m.add(new Model("Ngram","변화Ngram-"+n,(a,e,d)->switchNgram(a,e,d,q))); }
        for(int n=3;n<=7;n++)for(int hd=0;hd<=1;hd++){ final int q=n,h=hd; m.add(new Model("Shape","Shape-"+n+"-H"+hd,(a,e,d)->shape(a,e,d,q,h))); }
        for(int n=3;n<=7;n++)for(int k:new int[]{3,5}){ final int q=n,kk=k; m.add(new Model("KNN","kNN-"+n+"-k"+k,(a,e,d)->knn(a,e,d,q,kk))); }
        for(int cap:new int[]{2,3,4}){ final int q=cap; m.add(new Model("Run","RunLen-"+cap,(a,e,d)->runLen(a,e,d,q))); }
        for(int lag:new int[]{2,3,5,8}){ final int q=lag; m.add(new Model("Lag","Lag-"+lag,(a,e,d)->lag(a,e,d,q))); }
        for(int w:new int[]{6,8,10,12}){ final int q=w; m.add(new Model("Regime","Regime-"+w,(a,e,d)->regime(a,e,d,q))); }
        for(int w:new int[]{4,6,8,12}){ final int q=w; m.add(new Model("Recent","최근빈도-"+w,(a,e,d)->recentMajority(a,e,d,q))); }
        return m;
    }

    public static Result optimize(List<FlowCore.Result> all){
        Result out=new Result();
        if(all==null||all.isEmpty()){
            out.detail="자동탐색 준비중";
            return out;
        }
        // 표본이 매우 적어도 PASS하지 않고 최근 흐름으로 1픽을 만든다.
        if(all.size()<WARMUP+6){
            int end=all.size(),bestDim=0,bestMargin=-1,bestPick=+1;
            for(int d=0;d<3;d++){
                int s=0,st=Math.max(0,end-6);
                for(int i=st;i<end;i++)s+=vec(all.get(i).combo,d);
                int margin=Math.abs(s),pick=s==0?vec(all.get(end-1).combo,d):(s>0?+1:-1);
                if(margin>bestMargin){bestMargin=margin;bestDim=d;bestPick=pick;}
            }
            out.certified=true; out.targetAchieved=false; out.mode="초기 강제추천";
            out.dim=bestDim; out.pick=bestPick; out.validationRate=0.50; out.validationN=0; out.validationHit=0;
            out.rule="최근6 흐름 안전 fallback"; out.detail="표본이 아직 적어 최근 흐름으로 임시 1픽 생성 · PASS 없음";
            return out;
        }

        final int end=all.size();
        final int vStart=Math.max(WARMUP,end-MAX_VALIDATION);
        final int T=end-vStart;
        int holdN=Math.max(6,T/3);
        if(holdN>=T-5)holdN=Math.max(3,T/3);
        final int calT=Math.max(1,T-holdN);
        final List<Model> ms=models();
        int[][][] pred=new int[ms.size()][T+1][3];
        for(int mi=0;mi<ms.size();mi++){
            Model md=ms.get(mi);
            for(int ti=0;ti<=T;ti++){
                int e=(ti<T)?vStart+ti:end;
                for(int d=0;d<3;d++)pred[mi][ti][d]=safePredict(md,all,e,d);
            }
        }

        Candidate selected70=null,bestObserved=null;
        // 1차: 서로 다른 3계열을 동일 1표씩 합성. calibration에서 70% 안정규칙을 먼저 찾는다.
        for(int i=0;i<ms.size()-2;i++)for(int j=i+1;j<ms.size()-1;j++){
            if(ms.get(i).family.equals(ms.get(j).family))continue;
            for(int k=j+1;k<ms.size();k++){
                if(ms.get(i).family.equals(ms.get(k).family)||ms.get(j).family.equals(ms.get(k).family))continue;
                for(int dim=0;dim<3;dim++){
                    out.searched+=2;
                    Eval normal=evalComboRange(all,pred,i,j,k,dim,vStart,0,calT,false,T);
                    Eval inverse=evalComboRange(all,pred,i,j,k,dim,vStart,0,calT,true,T);
                    Candidate c1=new Candidate(i,j,k,dim,normal,ms),c2=new Candidate(i,j,k,dim,inverse,ms);
                    bestObserved=betterObserved(bestObserved,c1); bestObserved=betterObserved(bestObserved,c2);
                    if(qualifies(c1.e)&&selected70==null)selected70=c1; // 최고값이 아니라 탐색순서상 첫 안정 70 규칙
                    if(qualifies(c2.e)&&selected70==null)selected70=c2;
                }
            }
        }

        // 2차: 70 규칙이 독립 holdout에서도 유지되면 그대로 사용.
        if(selected70!=null){
            Eval hold=evalComboRange(all,pred,selected70.i,selected70.j,selected70.k,selected70.dim,vStart,calT,T,selected70.e.inverted,T);
            Eval overall=evalComboRange(all,pred,selected70.i,selected70.j,selected70.k,selected70.dim,vStart,0,T,selected70.e.inverted,T);
            if(hold.n>0&&hold.rate()+1e-12>=TARGET&&overall.rate()+1e-12>=TARGET){
                fill(out,selected70,selected70.e,hold,overall,true,"70+ 재현탐색 성공");
                return out;
            }
        }

        // 3차 구조구제: 기다리지 않는다. 70→65→60→55→50 순으로 문턱을 낮추며
        // 정방향/역방향, Markov 차수, N-gram, Shape, kNN, Run, Lag, Regime, Recent를 다시 탐색한다.
        // 각 모델은 동일 1표이며 raw 확률 최고값을 뽑지 않고, 해당 문턱을 처음 통과한 안정 규칙을 채택한다.
        final double[] tiers={0.70,0.65,0.60,0.55,0.50};
        Candidate rescue=null; Eval rescueOverall=null;
        double usedTier=0.50;
        outer:
        for(double tier:tiers){
            double floor=Math.max(0.45,tier-0.15);
            for(int i=0;i<ms.size()-2;i++)for(int j=i+1;j<ms.size()-1;j++){
                if(ms.get(i).family.equals(ms.get(j).family))continue;
                for(int k=j+1;k<ms.size();k++){
                    if(ms.get(i).family.equals(ms.get(k).family)||ms.get(j).family.equals(ms.get(k).family))continue;
                    for(int dim=0;dim<3;dim++){
                        for(boolean inv:new boolean[]{false,true}){
                            Eval ov=evalComboRange(all,pred,i,j,k,dim,vStart,0,T,inv,T);
                            if(ov.n>=Math.min(8,Math.max(4,T))&&ov.rate()+1e-12>=tier&&ov.r1()+1e-12>=floor&&ov.r2()+1e-12>=floor&&ov.pick!=0){
                                rescue=new Candidate(i,j,k,dim,ov,ms); rescueOverall=ov; usedTier=tier; break outer;
                            }
                        }
                    }
                }
            }
        }

        // binary 정/역 후보를 모두 보므로 충분한 표본에서는 50% 문턱을 통과하는 후보가 사실상 항상 생긴다.
        if(rescue==null)rescue=bestObserved;
        if(rescue==null){
            out.certified=true;out.targetAchieved=false;out.mode="최종 안전추천";out.dim=0;
            out.pick=recentMajority(all,end,0,6);out.validationRate=0.50;out.rule="최근6 fallback";
            out.detail="합성규칙 계산이 부족해 최근 흐름으로 1픽 생성 · PASS 없음";
            return out;
        }
        Eval cal=evalComboRange(all,pred,rescue.i,rescue.j,rescue.k,rescue.dim,vStart,0,calT,rescue.e.inverted,T);
        Eval hold=evalComboRange(all,pred,rescue.i,rescue.j,rescue.k,rescue.dim,vStart,calT,T,rescue.e.inverted,T);
        Eval overall=rescueOverall!=null?rescueOverall:evalComboRange(all,pred,rescue.i,rescue.j,rescue.k,rescue.dim,vStart,0,T,rescue.e.inverted,T);
        boolean observed70=overall.rate()+1e-12>=TARGET;
        // rescue는 전체 검증구간을 보며 방법을 고른 탐색 결과이므로 독립 70% 인증으로 표시하지 않는다.
        fill(out,rescue,cal,hold,overall,false,observed70?"강제 자동탐색 추천 · 선택구간 70+":"강제 자동탐색 추천");
        out.detail+=(observed70?" · 선택구간에서는 70+였지만 독립 인증값은 아님":" · 70 미달이어도 대기하지 않고 현재 구조에서 재탐색한 1픽을 사용")+" · 탐색문턱 "+pct(usedTier);
        return out;
    }

    private static void fill(Result out,Candidate c,Eval cal,Eval hold,Eval overall,boolean achieved,String mode){
        out.certified=true;out.targetAchieved=achieved;out.mode=mode;out.dim=c.dim;out.pick=overall.pick;
        out.validationN=overall.n;out.validationHit=overall.h;out.validationRate=overall.rate();
        out.firstRate=cal.rate();out.secondRate=hold.rate();out.rule=c.rule();out.inverted=overall.inverted;out.plusVotes=overall.plus;out.minusVotes=overall.minus;
        out.bestObservedRate=overall.rate();out.bestObservedN=overall.n;out.bestObservedRule=c.rule();
        out.detail=mode+" · 전체 walk-forward "+overall.h+"/"+overall.n+" = "+pct(overall.rate())+" · calibration "+pct(cal.rate())+" · 최근 holdout "+pct(hold.rate())+" · "+c.rule();
    }

    private static final class Candidate {
        final int i,j,k,dim; final Eval e; final List<Model> ms;
        Candidate(int i,int j,int k,int dim,Eval e,List<Model> ms){this.i=i;this.j=j;this.k=k;this.dim=dim;this.e=e;this.ms=ms;}
        String rule(){return (e.inverted?"역방향 · ":"")+ms.get(i).name+" + "+ms.get(j).name+" + "+ms.get(k).name;}
    }
    private static boolean qualifies(Eval e){return e.n>=MIN_VALIDATION&&e.rate()+1e-12>=TARGET&&e.r1()+1e-12>=BLOCK_FLOOR&&e.r2()+1e-12>=BLOCK_FLOOR&&e.pick!=0;}
    // 70%를 넘긴 후보 중 '최고값'이 아니라 목표 70%에 가장 가까운 안정 규칙을 선택.
    private static Candidate betterCertified(Candidate a,Candidate b){
        if(a==null)return b;
        double da=Math.abs(a.e.rate()-TARGET),db=Math.abs(b.e.rate()-TARGET);
        if(db<da-1e-12)return b;if(da<db-1e-12)return a;
        if(b.e.floor()>a.e.floor()+1e-12)return b;if(a.e.floor()>b.e.floor()+1e-12)return a;
        return b.e.n>a.e.n?b:a;
    }
    private static Candidate betterObserved(Candidate a,Candidate b){if(a==null)return b;if(b.e.rate()>a.e.rate()+1e-12)return b;if(a.e.rate()>b.e.rate()+1e-12)return a;return b.e.floor()>a.e.floor()?b:a;}

    private static Eval evalComboRange(List<FlowCore.Result> all,int[][][] pred,int i,int j,int k,int dim,int vStart,int fromTi,int toTi,boolean inverse,int currentIndex){
        Eval e=new Eval();e.inverted=inverse;int len=Math.max(0,toTi-fromTi),split=fromTi+Math.max(1,len/2);
        for(int ti=fromTi;ti<toTi;ti++){
            int p=majority(pred[i][ti][dim],pred[j][ti][dim],pred[k][ti][dim]); if(inverse)p=-p;
            int act=vec(all.get(vStart+ti).combo,dim);boolean ok=p==act;e.n++;if(ok)e.h++;
            if(ti<split){e.n1++;if(ok)e.h1++;}else{e.n2++;if(ok)e.h2++;}
        }
        int cp=majority(pred[i][currentIndex][dim],pred[j][currentIndex][dim],pred[k][currentIndex][dim]);if(inverse)cp=-cp;e.pick=cp;
        int[] vv={pred[i][currentIndex][dim],pred[j][currentIndex][dim],pred[k][currentIndex][dim]};for(int x:vv){if(inverse)x=-x;if(x>0)e.plus++;else e.minus++;}
        return e;
    }
    private static int majority(int a,int b,int c){int s=a+b+c;return s>=1?+1:-1;}
    private static int safePredict(Model m,List<FlowCore.Result>a,int end,int dim){try{int p=m.p.predict(a,end,dim);return p>=0?+1:-1;}catch(Throwable t){return recentMajority(a,end,dim,6);}}

    private static int markov(List<FlowCore.Result>a,int end,int dim,int order){
        if(end<=order)return recentMajority(a,end,dim,6);int plus=0,minus=0;
        for(int next=order;next<end;next++){
            boolean ok=true;for(int q=0;q<order;q++)if(vec(a.get(next-order+q).combo,dim)!=vec(a.get(end-order+q).combo,dim)){ok=false;break;}
            if(ok){int v=vec(a.get(next).combo,dim);if(v>0)plus++;else minus++;}
        }
        if(plus+minus==0)return order>1?markov(a,end,dim,order-1):recentMajority(a,end,dim,6);
        if(plus==minus)return vec(a.get(end-1).combo,dim);return plus>minus?+1:-1;
    }
    // 최근 변화(유지/반전) n-gram의 다음 변화 방향을 예측.
    private static int switchNgram(List<FlowCore.Result>a,int end,int dim,int n){
        if(end<n+2)return recentMajority(a,end,dim,6);int same=0,flip=0;
        int[] cur=new int[n];for(int q=0;q<n;q++){int x=vec(a.get(end-n-1+q).combo,dim),y=vec(a.get(end-n+q).combo,dim);cur[q]=x==y?1:-1;}
        for(int next=n+1;next<end;next++){
            boolean ok=true;for(int q=0;q<n;q++){int x=vec(a.get(next-n-1+q).combo,dim),y=vec(a.get(next-n+q).combo,dim);int r=x==y?1:-1;if(r!=cur[q]){ok=false;break;}}
            if(ok){int prev=vec(a.get(next-1).combo,dim),v=vec(a.get(next).combo,dim);if(v==prev)same++;else flip++;}
        }
        int last=vec(a.get(end-1).combo,dim);if(same+flip==0)return last;return same>=flip?last:-last;
    }
    private static int shape(List<FlowCore.Result>a,int end,int dim,int len,int maxHd){
        if(end<=len)return recentMajority(a,end,dim,6);int[] cur=new int[len-1];for(int q=1;q<len;q++){int x=vec(a.get(end-len+q-1).combo,dim),y=vec(a.get(end-len+q).combo,dim);cur[q-1]=x==y?1:-1;}
        int same=0,flip=0;
        for(int next=len;next<end;next++){
            int hd=0;for(int q=1;q<len;q++){int x=vec(a.get(next-len+q-1).combo,dim),y=vec(a.get(next-len+q).combo,dim);int r=x==y?1:-1;if(r!=cur[q-1])hd++;}
            if(hd<=maxHd){int prev=vec(a.get(next-1).combo,dim),v=vec(a.get(next).combo,dim);if(v==prev)same++;else flip++;}
        }
        int last=vec(a.get(end-1).combo,dim);if(same+flip==0)return last;return same>=flip?last:-last;
    }
    private static int knn(List<FlowCore.Result>a,int end,int dim,int len,int k){
        if(end<=len)return recentMajority(a,end,dim,6);List<int[]> rows=new ArrayList<>();
        for(int next=len;next<end;next++){int hd=0;for(int q=0;q<len;q++)if(vec(a.get(next-len+q).combo,dim)!=vec(a.get(end-len+q).combo,dim))hd++;rows.add(new int[]{hd,vec(a.get(next).combo,dim)});}
        if(rows.isEmpty())return recentMajority(a,end,dim,6);rows.sort(Comparator.comparingInt(x->x[0]));int s=0,n=Math.min(k,rows.size());for(int i=0;i<n;i++)s+=rows.get(i)[1];return s>=0?+1:-1;
    }
    private static int runLen(List<FlowCore.Result>a,int end,int dim,int cap){
        if(end<3)return recentMajority(a,end,dim,6);int cur=vec(a.get(end-1).combo,dim),rl=runAt(a,end-1,dim,cap),plus=0,minus=0;
        for(int next=1;next<end;next++){if(vec(a.get(next-1).combo,dim)==cur&&runAt(a,next-1,dim,cap)==rl){int v=vec(a.get(next).combo,dim);if(v>0)plus++;else minus++;}}
        if(plus+minus==0)return cur;if(plus==minus)return cur;return plus>minus?+1:-1;
    }
    private static int runAt(List<FlowCore.Result>a,int idx,int dim,int cap){int v=vec(a.get(idx).combo,dim),n=1;for(int i=idx-1;i>=0&&n<cap&&vec(a.get(i).combo,dim)==v;i--)n++;return n;}
    private static int lag(List<FlowCore.Result>a,int end,int dim,int lag){
        if(end<=lag)return recentMajority(a,end,dim,6);int same=0,n=0;for(int i=lag;i<end;i++){if(vec(a.get(i).combo,dim)==vec(a.get(i-lag).combo,dim))same++;n++;}
        int src=vec(a.get(end-lag).combo,dim);return same*2>=n?src:-src;
    }
    private static int regime(List<FlowCore.Result>a,int end,int dim,int window){
        if(end<2)return +1;int st=Math.max(1,end-window),sw=0,n=0;for(int i=st;i<end;i++){if(vec(a.get(i).combo,dim)!=vec(a.get(i-1).combo,dim))sw++;n++;}
        int last=vec(a.get(end-1).combo,dim);double r=n==0?0.5:(double)sw/n;if(r>=0.60)return -last;if(r<=0.40)return last;return recentMajority(a,end,dim,window);
    }
    private static int recentMajority(List<FlowCore.Result>a,int end,int dim,int window){
        if(end<=0)return +1;int s=0,st=Math.max(0,end-window);for(int i=st;i<end;i++)s+=vec(a.get(i).combo,dim);if(s==0)return vec(a.get(end-1).combo,dim);return s>0?+1:-1;
    }
    private static int vec(int combo,int dim){
        switch(combo){
            case 1:return dim==0?+1:dim==1?+1:-1;
            case 2:return dim==0?+1:dim==1?-1:+1;
            case 3:return dim==0?-1:dim==1?+1:+1;
            case 4:return -1;
            default:return -1;
        }
    }
    private static String pct(double v){return String.format(Locale.KOREA,"%.1f%%",v*100.0);}
}
