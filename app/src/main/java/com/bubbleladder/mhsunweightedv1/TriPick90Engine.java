package com.bubbleladder.mhsunweightedv1;

import java.util.*;

/**
 * 4개 조합 중 1개를 제외하고 나머지 3개를 추천하는 '삼치기' 90% 목표 엔진.
 *
 * 중요한 원칙
 * - 90%라는 숫자를 임의로 만들지 않는다.
 * - 각 과거 시점에서 그 시점 이후 결과를 가린 채 어떤 조합을 제외했을지 walk-forward로 재현한다.
 * - 후보 선택 구간(pre-holdout)과 마지막 holdout을 분리한다.
 * - pre-holdout에서만 방법을 선택하고, 선택 후 마지막 holdout을 단 한 번 검사한다.
 * - 두 구간 및 전체가 90% 이상일 때만 targetAchieved=true.
 * - 90% 미달이어도 멈추지 않고, 현재 데이터에서 가장 낮은 발생위험 조합 1개를 반드시 제외한다.
 * - targetAchieved는 독립검증 90% 통과 여부이며, 추천 자체는 매 회차 생성한다.
 */
public final class TriPick90Engine {
    private TriPick90Engine(){}

    public static final double TARGET = 0.90;
    private static final double PRE_BLOCK_FLOOR = 0.85;
    private static final int WARMUP = 24;
    private static final int MAX_VALIDATION = 100;
    private static final int MIN_PRE = 24;
    private static final int MIN_HOLD = 10;

    public static final class Result {
        public boolean certified;
        public boolean targetAchieved;
        public int excludedCombo=0;
        public int validationN=0, validationHit=0, preN=0, preHit=0, holdN=0, holdHit=0, searched=0;
        public double validationRate=0.0, preRate=0.0, holdRate=0.0, firstRate=0.0, secondRate=0.0;
        public double bestObservedRate=0.0;
        public String mode="-", rule="-", detail="-", picksLabel="-", excludedLabel="-";
    }

    private interface Excluder { int exclude(List<FlowCore.Result> a,int end); }
    private static final class Strategy {
        final String family,name; final Excluder x;
        Strategy(String family,String name,Excluder x){this.family=family;this.name=name;this.x=x;}
    }
    private static final class Eval {
        int n,h,n1,h1,n2,h2,excluded;
        double rate(){return n==0?0:(double)h/n;}
        double r1(){return n1==0?0:(double)h1/n1;}
        double r2(){return n2==0?0:(double)h2/n2;}
        double floor(){return Math.min(r1(),r2());}
    }
    private static final class Candidate {
        final int i,j,k; final Eval pre; final List<Strategy> ss;
        Candidate(int i,int j,int k,Eval pre,List<Strategy> ss){this.i=i;this.j=j;this.k=k;this.pre=pre;this.ss=ss;}
        boolean single(){return j<0;}
        String rule(){
            if(single())return ss.get(i).name;
            return ss.get(i).name+" + "+ss.get(j).name+" + "+ss.get(k).name;
        }
    }

    public static Result optimize(List<FlowCore.Result> all){
        Result out=new Result();
        if(all==null||all.isEmpty()){out.detail="삼치기 데이터 준비중";return out;}
        int end=all.size();

        // 표본이 적어도 추천은 멈추지 않는다. 최근 구간에서 가장 희소한 조합 1개를 제외해 항상 삼치기 3픽을 만든다.
        if(end<WARMUP+MIN_HOLD){
            int ex=bootstrapExclude(all,end);
            Eval boot=bootstrapEval(all,end);
            out.certified=true;out.targetAchieved=boot.n>=MIN_HOLD && boot.rate()+1e-12>=TARGET;
            out.mode=out.targetAchieved?"🔥 초기 90+":"🎯 META90 초기 강제 1픽";
            out.excludedCombo=ex;out.excludedLabel=combo(ex);out.picksLabel=includeLabel(ex);
            out.validationN=boot.n;out.validationHit=boot.h;out.validationRate=boot.rate();
            out.preN=boot.n;out.preHit=boot.h;out.preRate=boot.rate();out.holdN=0;out.holdHit=0;out.holdRate=0;
            out.rule="초기 희소조합 다중창 선택";
            out.detail="표본 "+end+"회 · 아직 독립검증 표본이 작아도 추천 중단 없이 제외 "+combo(ex)+" · 재현 "+boot.h+"/"+boot.n+" = "+pct(boot.rate());
            return out;
        }

        final int vStart=Math.max(WARMUP,end-MAX_VALIDATION);
        final int T=end-vStart;
        int holdN=Math.max(MIN_HOLD,T/4);
        if(holdN>=T-MIN_PRE)holdN=Math.max(MIN_HOLD,T/5);
        final int preT=Math.max(1,T-holdN);
        final List<Strategy> ss=strategies();

        // 각 전략의 과거시점 및 현재시점 제외 조합을 미리 계산한다.
        int[][] exPred=new int[ss.size()][T+1];
        for(int s=0;s<ss.size();s++){
            for(int ti=0;ti<=T;ti++){
                int e=(ti<T)?vStart+ti:end;
                exPred[s][ti]=safeExclude(ss.get(s),all,e);
            }
        }

        Candidate selected90=null,bestObserved=null;

        // 1) 단일 방법 후보. 선택은 pre-holdout만 본다. 단일 규칙에서 90이 나오면 복잡한 합성보다 우선한다.
        for(int i=0;i<ss.size();i++){
            out.searched++;
            Eval pre=eval(all,exPred,i,-1,-1,vStart,0,preT,T);
            Candidate c=new Candidate(i,-1,-1,pre,ss);
            bestObserved=betterObserved(bestObserved,c);
            if(qualifiesPre(pre))selected90=betterTarget(selected90,c);
        }

        // 2) 단일 방법에서 90 후보가 없을 때만 서로 다른 계열 3개를 동등 1표로 합성한다. 가중치 없음.
        if(selected90==null){
            for(int i=0;i<ss.size()-2;i++)for(int j=i+1;j<ss.size()-1;j++){
                if(ss.get(i).family.equals(ss.get(j).family))continue;
                for(int k=j+1;k<ss.size();k++){
                    if(ss.get(i).family.equals(ss.get(k).family)||ss.get(j).family.equals(ss.get(k).family))continue;
                    out.searched++;
                    Eval pre=eval(all,exPred,i,j,k,vStart,0,preT,T);
                    Candidate c=new Candidate(i,j,k,pre,ss);
                    bestObserved=betterObserved(bestObserved,c);
                    if(qualifiesPre(pre))selected90=betterTarget(selected90,c);
                }
            }
        }

        // 최종 holdout은 후보 선택에 사용하지 않는다. 선택된 후보를 딱 한 번 검사한다.
        if(selected90!=null){
            Eval hold=eval(all,exPred,selected90.i,selected90.j,selected90.k,vStart,preT,T,T);
            Eval overall=eval(all,exPred,selected90.i,selected90.j,selected90.k,vStart,0,T,T);
            boolean achieved=hold.n>=MIN_HOLD && hold.rate()+1e-12>=TARGET && hold.r1()+1e-12>=TARGET && hold.r2()+1e-12>=TARGET && overall.rate()+1e-12>=TARGET;
            if(achieved){
                fill(out,selected90,hold,overall,true,"🔥 삼치기 90+ 독립 holdout 통과");
                return out;
            }
            // pre에서 90을 넘겼지만 독립 holdout이 무너져도 멈추지 않는다.
            // 그 후보와 전체 후보 중 더 안정적인 쪽을 현재 회차 1픽으로 사용한다.
            Candidate force=betterObserved(selected90,bestObserved);
            Eval fhold=eval(all,exPred,force.i,force.j,force.k,vStart,preT,T,T);
            Eval foverall=eval(all,exPred,force.i,force.j,force.k,vStart,0,T,T);
            fill(out,force,fhold,foverall,false,"🎯 META90 강제 1픽 · 90 독립검증 미달");
            out.detail+=" · 재탐색 대기 없이 현재 최저위험 제외픽을 즉시 추천";
            return out;
        }

        // 90 후보가 없어도 매 회차 반드시 1개를 제외한다. bestObserved는 미래를 보지 않은 walk-forward 성적 기준이다.
        if(bestObserved==null){
            int ex=bootstrapExclude(all,end);Eval boot=bootstrapEval(all,end);
            out.certified=true;out.targetAchieved=false;out.mode="🎯 META90 강제 1픽";
            out.excludedCombo=ex;out.excludedLabel=combo(ex);out.picksLabel=includeLabel(ex);
            out.validationN=boot.n;out.validationHit=boot.h;out.validationRate=boot.rate();
            out.preN=boot.n;out.preHit=boot.h;out.preRate=boot.rate();out.rule="다중창 희소조합";
            out.detail="90+ 독립후보는 없지만 추천 중단 없음 · 현재 최저위험 제외 "+combo(ex)+" · 재현 "+boot.h+"/"+boot.n+" = "+pct(boot.rate());
            return out;
        }
        Eval hold=eval(all,exPred,bestObserved.i,bestObserved.j,bestObserved.k,vStart,preT,T,T);
        Eval overall=eval(all,exPred,bestObserved.i,bestObserved.j,bestObserved.k,vStart,0,T,T);
        fill(out,bestObserved,hold,overall,false,"🎯 META90 강제 1픽 · 90 목표 미달");
        out.detail+=" · 재탐색 상태로 멈추지 않고 현재 최저위험 제외픽을 추천";
        return out;
    }

    private static void fill(Result out,Candidate c,Eval hold,Eval overall,boolean achieved,String mode){
        out.certified=true;out.targetAchieved=achieved;out.mode=mode;
        out.excludedCombo=overall.excluded;out.excludedLabel=combo(overall.excluded);out.picksLabel=includeLabel(overall.excluded);
        out.validationN=overall.n;out.validationHit=overall.h;out.validationRate=overall.rate();
        out.preN=c.pre.n;out.preHit=c.pre.h;out.preRate=c.pre.rate();
        out.holdN=hold.n;out.holdHit=hold.h;out.holdRate=hold.rate();
        out.firstRate=c.pre.r1();out.secondRate=c.pre.r2();out.bestObservedRate=overall.rate();out.rule=c.rule();
        out.detail=mode+" · 전체 walk-forward "+overall.h+"/"+overall.n+" = "+pct(overall.rate())+
                " · 선택구간 "+c.pre.h+"/"+c.pre.n+" = "+pct(c.pre.rate())+
                " · 마지막 holdout "+hold.h+"/"+hold.n+" = "+pct(hold.rate())+" (전반 "+pct(hold.r1())+" / 후반 "+pct(hold.r2())+")"+
                " · 제외 "+combo(overall.excluded)+" · "+c.rule();
    }

    private static boolean qualifiesPre(Eval e){
        return e.n>=MIN_PRE && e.rate()+1e-12>=TARGET && e.r1()+1e-12>=PRE_BLOCK_FLOOR && e.r2()+1e-12>=PRE_BLOCK_FLOOR && validCombo(e.excluded);
    }

    // 90%를 넘는 후보들 중에는 전/후반 최저 적중률을 먼저 보고, 그 다음 전체 적중률을 본다.
    // 같은 수준이면 단일 규칙을 우선해 불필요하게 복잡한 조합의 우연 적합을 줄인다.
    private static Candidate betterTarget(Candidate a,Candidate b){
        if(a==null)return b;
        if(b.pre.floor()>a.pre.floor()+1e-12)return b;if(a.pre.floor()>b.pre.floor()+1e-12)return a;
        if(b.pre.rate()>a.pre.rate()+1e-12)return b;if(a.pre.rate()>b.pre.rate()+1e-12)return a;
        if(b.single()&&!a.single())return b;if(a.single()&&!b.single())return a;
        return b.pre.n>a.pre.n?b:a;
    }

    // 90 후보가 없을 때는 단순 최고 적중률만 보지 않고 전/후반 최저값을 먼저 본다.
    private static Candidate betterObserved(Candidate a,Candidate b){
        if(a==null)return b;
        if(b.pre.floor()>a.pre.floor()+1e-12)return b;if(a.pre.floor()>b.pre.floor()+1e-12)return a;
        if(b.pre.rate()>a.pre.rate()+1e-12)return b;if(a.pre.rate()>b.pre.rate()+1e-12)return a;
        return b.pre.n>a.pre.n?b:a;
    }

    private static Eval eval(List<FlowCore.Result> all,int[][] exPred,int i,int j,int k,int vStart,int from,int to,int currentIndex){
        Eval e=new Eval();int len=Math.max(0,to-from),split=from+Math.max(1,len/2);
        for(int ti=from;ti<to;ti++){
            int ex=ensemble(exPred,i,j,k,ti);
            int actual=all.get(vStart+ti).combo;
            boolean ok=actual!=ex;
            e.n++;if(ok)e.h++;
            if(ti<split){e.n1++;if(ok)e.h1++;}else{e.n2++;if(ok)e.h2++;}
        }
        e.excluded=ensemble(exPred,i,j,k,currentIndex);
        return e;
    }

    private static int ensemble(int[][] p,int i,int j,int k,int t){
        int a=p[i][t];if(j<0)return validCombo(a)?a:4;
        int b=p[j][t],c=p[k][t];
        int[] v=new int[5];if(validCombo(a))v[a]++;if(validCombo(b))v[b]++;if(validCombo(c))v[c]++;
        int best=1,cnt=v[1];for(int x=2;x<=4;x++)if(v[x]>cnt){best=x;cnt=v[x];}
        if(cnt>=2)return best;
        // 1:1:1 동률이면 첫 전략의 판단을 그대로 사용해 임의 가중치를 넣지 않는다.
        return validCombo(a)?a:4;
    }

    private static int safeExclude(Strategy s,List<FlowCore.Result> a,int end){
        try{int x=s.x.exclude(a,end);return validCombo(x)?x:recentRare(a,end,Math.min(12,end));}
        catch(Throwable t){return recentRare(a,end,Math.min(12,end));}
    }

    private static List<Strategy> strategies(){
        List<Strategy> s=new ArrayList<>();
        // 90%가 안 나오면 짧은/중간/긴 구간을 모두 훑는다. 가중치는 쓰지 않는다.
        for(int w:new int[]{4,5,6,7,8,9,10,12,14,16,20,24,30,40,50,60,80,100,120}){final int q=w;s.add(new Strategy("Recent","최근희소-"+w,(a,e)->recentRare(a,e,q)));}
        for(int o:new int[]{1,2,3,4,5,6}){final int q=o;s.add(new Strategy("Markov","ComboMarkov-o"+o,(a,e)->markovRare(a,e,q)));}
        for(int w:new int[]{12,16,20,24,30,40,50,60,80,100,120}){final int q=w;s.add(new Strategy("Transition","전이희소-"+w,(a,e)->transitionRare(a,e,q)));}
        for(int n=3;n<=10;n++)for(int hd:new int[]{0,1,2}){final int q=n,h=hd;s.add(new Strategy("Shape","ShapeCombo-"+n+"-H"+hd,(a,e)->shapeRare(a,e,q,h)));}
        for(int n=3;n<=10;n++)for(int k:new int[]{3,5,7,9}){final int q=n,kk=k;s.add(new Strategy("KNN","ComboKNN-"+n+"-k"+k,(a,e)->knnRare(a,e,q,kk)));}
        for(int cap:new int[]{2,3,4,5,6}){final int q=cap;s.add(new Strategy("Run","RunCombo-"+cap,(a,e)->runRare(a,e,q)));}
        for(int lag:new int[]{1,2,3,4,5,6,7,8,9,10,11,12}){final int q=lag;s.add(new Strategy("Lag","LagCombo-"+lag,(a,e)->lagRare(a,e,q)));}
        // META: 과거 최근구간에서 실제 제외 실패(actual==excluded)가 가장 적었던 핵심 규칙을 매 시점 새로 선택한다.
        for(int lb:new int[]{12,20,32}){final int q=lb;s.add(new Strategy("Meta","META90-BestRecent-"+lb,(a,e)->metaBestRecent(a,e,q)));}
        return s;
    }

    // 핵심 규칙 중 최근 walk-forward 실패가 가장 적은 규칙을 동적으로 고른다. 가중치 합산 없이 규칙 1개를 선택한다.
    private static int metaBestRecent(List<FlowCore.Result>a,int end,int lookback){
        if(end<8)return bootstrapExclude(a,end);
        List<Strategy> core=metaCoreStrategies();
        int from=Math.max(4,end-Math.max(6,lookback));
        Strategy best=null;int bestMiss=Integer.MAX_VALUE,bestN=-1,bestRecentMiss=Integer.MAX_VALUE;
        for(Strategy st:core){
            int miss=0,n=0,recentMiss=0,cut=Math.max(from,end-Math.max(4,lookback/3));
            for(int t=from;t<end;t++){
                int ex=safeExclude(st,a,t);int actual=a.get(t).combo;if(!validCombo(actual))continue;
                n++;if(actual==ex){miss++;if(t>=cut)recentMiss++;}
            }
            if(n==0)continue;
            if(best==null||miss<bestMiss||(miss==bestMiss&&recentMiss<bestRecentMiss)||(miss==bestMiss&&recentMiss==bestRecentMiss&&n>bestN)){
                best=st;bestMiss=miss;bestRecentMiss=recentMiss;bestN=n;
            }
        }
        return best==null?bootstrapExclude(a,end):safeExclude(best,a,end);
    }

    private static List<Strategy> metaCoreStrategies(){
        List<Strategy> s=new ArrayList<>();
        for(int w:new int[]{6,10,16,24,40,60}){final int q=w;s.add(new Strategy("Recent","R"+w,(a,e)->recentRare(a,e,q)));}
        for(int o:new int[]{1,2,3}){final int q=o;s.add(new Strategy("Markov","M"+o,(a,e)->markovRare(a,e,q)));}
        for(int w:new int[]{20,40,60}){final int q=w;s.add(new Strategy("Transition","T"+w,(a,e)->transitionRare(a,e,q)));}
        for(int n:new int[]{4,6,8}){final int q=n;s.add(new Strategy("Shape","S"+n,(a,e)->shapeRare(a,e,q,1)));}
        for(int lag:new int[]{1,2,3,5}){final int q=lag;s.add(new Strategy("Lag","L"+lag,(a,e)->lagRare(a,e,q)));}
        return s;
    }

    // 적은 표본에서도 '대기' 대신 5개 최근창을 한 표씩 비교해 가장 적게 나온 조합을 제외한다.
    private static int bootstrapExclude(List<FlowCore.Result>a,int end){
        if(end<=0)return 4;
        int[] vote=new int[5];
        for(int w:new int[]{4,6,8,12,20}){int ex=recentRare(a,end,Math.min(w,end));if(validCombo(ex))vote[ex]++;}
        int best=1,max=vote[1];for(int c=2;c<=4;c++)if(vote[c]>max){best=c;max=vote[c];}
        return best;
    }

    private static Eval bootstrapEval(List<FlowCore.Result>a,int end){
        Eval e=new Eval();int from=Math.max(2,end-20),split=from+Math.max(1,(end-from)/2);
        for(int t=from;t<end;t++){int ex=bootstrapExclude(a,t);int actual=a.get(t).combo;if(!validCombo(actual))continue;boolean ok=actual!=ex;e.n++;if(ok)e.h++;if(t<split){e.n1++;if(ok)e.h1++;}else{e.n2++;if(ok)e.h2++;}}
        e.excluded=bootstrapExclude(a,end);return e;
    }


    private static int recentRare(List<FlowCore.Result>a,int end,int window){
        if(end<=0)return 4;int[] c={0,0,0,0,0};int st=Math.max(0,end-Math.max(1,window));
        for(int i=st;i<end;i++)if(validCombo(a.get(i).combo))c[a.get(i).combo]++;
        return leastCount(c,lastCombo(a,end));
    }

    private static int markovRare(List<FlowCore.Result>a,int end,int order){
        if(end<=order)return recentRare(a,end,12);
        int[] c={0,0,0,0,0};int m=0;
        for(int next=order;next<end;next++){
            boolean ok=true;
            for(int q=0;q<order;q++)if(a.get(next-order+q).combo!=a.get(end-order+q).combo){ok=false;break;}
            if(ok){int v=a.get(next).combo;if(validCombo(v)){c[v]++;m++;}}
        }
        if(m<2)return order>1?markovRare(a,end,order-1):transitionRare(a,end,40);
        return leastCount(c,lastCombo(a,end));
    }

    private static int transitionRare(List<FlowCore.Result>a,int end,int window){
        if(end<2)return recentRare(a,end,12);int last=a.get(end-1).combo;int[] c={0,0,0,0,0};int m=0,st=Math.max(1,end-window);
        for(int next=st;next<end;next++)if(a.get(next-1).combo==last){int v=a.get(next).combo;if(validCombo(v)){c[v]++;m++;}}
        if(m<2)return recentRare(a,end,window);
        return leastCount(c,last);
    }

    // 현재와 과거의 '모양'은 각 이동에서 좌/우·줄수·홀짝 중 무엇이 바뀌었는지 3비트로 표현한다.
    private static int shapeRare(List<FlowCore.Result>a,int end,int len,int maxHd){
        if(end<=len+1)return recentRare(a,end,12);
        int[] cur=transitionBits(a,end-len,end);
        int[] c={0,0,0,0,0};int m=0;
        for(int next=len;next<end;next++){
            int[] old=transitionBits(a,next-len,next);
            int hd=hammingBits(cur,old);
            if(hd<=maxHd){int v=a.get(next).combo;if(validCombo(v)){c[v]++;m++;}}
        }
        if(m<2)return markovRare(a,end,Math.min(2,len-1));
        return leastCount(c,lastCombo(a,end));
    }

    private static int[] transitionBits(List<FlowCore.Result>a,int from,int to){
        int steps=Math.max(0,to-from-1);int[] out=new int[steps*3];int z=0;
        for(int i=from+1;i<to;i++){
            int p=a.get(i-1).combo,n=a.get(i).combo;
            for(int d=0;d<3;d++)out[z++]=vec(p,d)==vec(n,d)?0:1;
        }
        return out;
    }
    private static int hammingBits(int[]a,int[]b){int n=Math.abs(a.length-b.length),m=Math.min(a.length,b.length);for(int i=0;i<m;i++)if(a[i]!=b[i])n++;return n;}

    private static int knnRare(List<FlowCore.Result>a,int end,int len,int k){
        if(end<=len+1)return recentRare(a,end,12);
        List<int[]> rows=new ArrayList<>();
        for(int next=len;next<end;next++){
            int dist=0;for(int q=0;q<len;q++)if(a.get(next-len+q).combo!=a.get(end-len+q).combo)dist++;
            rows.add(new int[]{dist,a.get(next).combo});
        }
        if(rows.isEmpty())return recentRare(a,end,12);
        rows.sort(Comparator.comparingInt(x->x[0]));int[] c={0,0,0,0,0};int n=Math.min(k,rows.size());
        for(int i=0;i<n;i++){int v=rows.get(i)[1];if(validCombo(v))c[v]++;}
        return leastCount(c,lastCombo(a,end));
    }

    private static int runRare(List<FlowCore.Result>a,int end,int cap){
        if(end<3)return recentRare(a,end,12);int last=lastCombo(a,end),run=1;
        for(int i=end-2;i>=0&&run<cap&&a.get(i).combo==last;i--)run++;
        int[] c={0,0,0,0,0};int m=0;
        for(int next=1;next<end;next++){
            if(a.get(next-1).combo!=last)continue;int rr=1;
            for(int i=next-2;i>=0&&rr<cap&&a.get(i).combo==last;i--)rr++;
            if(rr==run){int v=a.get(next).combo;if(validCombo(v)){c[v]++;m++;}}
        }
        if(m<2)return transitionRare(a,end,40);
        return leastCount(c,last);
    }

    private static int lagRare(List<FlowCore.Result>a,int end,int lag){
        if(end<=lag+2)return recentRare(a,end,12);int key=a.get(end-lag).combo;int[] c={0,0,0,0,0};int m=0;
        for(int next=lag;next<end;next++)if(a.get(next-lag).combo==key){int v=a.get(next).combo;if(validCombo(v)){c[v]++;m++;}}
        if(m<2)return recentRare(a,end,20);
        return leastCount(c,lastCombo(a,end));
    }

    private static int leastCount(int[] c,int avoidTie){
        int min=Integer.MAX_VALUE;for(int x=1;x<=4;x++)min=Math.min(min,c[x]);
        // 동률이면 직전 결과를 제외하는 편향을 피하고, 직전과 다른 첫 후보를 사용한다.
        for(int x=1;x<=4;x++)if(c[x]==min&&x!=avoidTie)return x;
        for(int x=1;x<=4;x++)if(c[x]==min)return x;
        return 4;
    }

    private static int lastCombo(List<FlowCore.Result>a,int end){return end>0&&validCombo(a.get(end-1).combo)?a.get(end-1).combo:4;}
    private static boolean validCombo(int c){return c>=1&&c<=4;}
    private static String combo(int c){return validCombo(c)?FlowCore.COMBO[c]:"-";}
    private static String includeLabel(int excluded){
        StringBuilder sb=new StringBuilder();for(int c=1;c<=4;c++)if(c!=excluded){if(sb.length()>0)sb.append(" · ");sb.append(FlowCore.COMBO[c]);}return sb.toString();
    }
    private static int vec(int combo,int dim){
        switch(combo){
            case 1:return dim==0?+1:dim==1?+1:-1;
            case 2:return dim==0?+1:dim==1?-1:+1;
            case 3:return dim==0?-1:dim==1?+1:+1;
            case 4:return -1;
            default:return 0;
        }
    }
    private static String pct(double v){return String.format(Locale.KOREA,"%.1f%%",v*100.0);}
}
