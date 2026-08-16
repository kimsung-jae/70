package com.bubbleladder.mhsunweightedv1;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

public final class FlowCore {
    private FlowCore(){}

    public static final String API="https://api.bepick.io/game/bubble_ladder3";
    public static final String PREF="bubble_target70_v12";
    public static final String ACTION_UPDATED="com.bubbleladder.target70v12.FLOW_UPDATED";
    public static final int WINDOW=150;
    public static final double PICK_THRESHOLD=0.70;

    public static final String K_HISTORY="history", K_RECORDS="records",
            K_PENDING_IDX="pending_idx", K_PENDING_DIM="pending_dim", K_PENDING_PICK="pending_pick",
            K_PENDING_CONF="pending_conf", K_PENDING_STAKE="pending_stake", K_PENDING_ODDS="pending_odds",
            K_LIVE_TOTAL="live_total", K_LIVE_SUCCESS="live_success", K_LIVE_PROFIT="live_profit",
            K_BASE_STAKE="base_stake", K_ODDS="odds", K_AUTO="auto_enabled",
            K_LAST_PICK="last_pick", K_LAST_CONF="last_conf", K_LAST_SYNC="last_sync", K_LAST_API_COUNT="last_api_count", K_LAST_STAGE="last_stage";

    public static final String[] COMBO={"","좌3짝","좌4홀","우3홀","우4짝"};
    public static final String[] DIM={"좌/우","사다리수","홀/짝"};
    // +1 = 좌 / 3줄 / 홀, -1 = 우 / 4줄 / 짝
    private static final int[][] VEC={{0,0,0},{+1,+1,-1},{+1,-1,+1},{-1,+1,+1},{-1,-1,-1}};

    public static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}

    public static final class Result { public long idx; public String date; public int round,combo; }

    public static final class EngineStat {
        public String name="-", detail="-";
        public int pick=0, samples=0;
        public double pPlus=0.5, confidence=0.5;
        public String label(String dim){
            return name+" → "+(pick==0?"중립":sideLabel(dim,pick))+" "+pct(confidence)+" · "+detail;
        }
    }

    public static final class ShapeStat {
        public int length,exactMatches,nearMatches,pick;
        public double sameWeight,flipWeight,pPlus=0.5,confidence=0.5,effectiveMatches;
        public boolean ready;
        public String shape="-", tendency="-";
        public String label(String dim){
            String dir=pick==0?"중립":sideLabel(dim,pick);
            return length+"칸 · "+shape+" · 완전 "+exactMatches+" / 유사 "+nearMatches+
                    " · "+tendency+" · "+dir+" "+pct(confidence);
        }
    }

    public static final class DimensionStat {
        public String name;
        public EngineStat markov,hmm,shape;
        public ShapeStat main3,confirm4,assist5;
        public int pick;
        public double confidence;
        public boolean qualified;
        public String verdict;
    }

    public static final class Backtest {
        public int globalN,globalHit,strongN,strongHit;
        public int[] dimN=new int[3],dimHit=new int[3],strongDimN=new int[3],strongDimHit=new int[3];
    }

    public static final class Analysis {
        public DimensionStat[] dims;
        public int bestDim=-1,bestPick=0,count;
        public double bestConfidence;
        public boolean bestStrong;
        public String bestLabel="대기",date="",windowRange="",suffix="";
        public Backtest backtest;
        public Target70Engine.Result target70;
    }

    public static final class SyncResult {
        public boolean newRoundResolved;
        public Analysis analysis;
        public List<Result> history;
    }

    public static List<Result> fetch() throws Exception{
        HttpURLConnection c=null;
        try{
            URL u=new URL(API+"?t="+System.currentTimeMillis());
            c=(HttpURLConnection)u.openConnection();
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000); c.setReadTimeout(15000); c.setUseCaches(false);
            c.setRequestProperty("Accept","application/json,text/plain,*/*");
            c.setRequestProperty("Accept-Encoding","identity");
            c.setRequestProperty("Cache-Control","no-cache");
            c.setRequestProperty("Pragma","no-cache");
            c.setRequestProperty("Connection","close");
            c.setRequestProperty("Referer","https://bepick.net/");
            c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/138 Mobile Safari/537.36");
            int code=c.getResponseCode();
            if(code<200||code>=300)throw new Exception("API HTTP "+code);
            BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));
            StringBuilder sb=new StringBuilder(); String line; while((line=br.readLine())!=null)sb.append(line); br.close();
            String raw=sb.toString().trim();
            if(raw.isEmpty())throw new Exception("API 응답 비어 있음");
            JSONObject root;
            try{ root=new JSONObject(raw); }
            catch(Throwable je){ throw new Exception("API JSON 형식 오류 · "+shortText(raw),je); }
            JSONArray arr=root.optJSONArray("data");
            if(arr==null)throw new Exception("API data 없음 · "+shortText(raw));
            List<Result> out=new ArrayList<>();
            for(int i=0;i<arr.length();i++){
                JSONObject o=arr.optJSONObject(i); if(o==null)continue;
                long idx=safeLong(o,"idx");
                int round=safeInt(o,"round");
                int f1=safeInt(o,"fd1"), f2=safeInt(o,"fd2"), f3=safeInt(o,"fd3"), f4=safeInt(o,"fd4");
                int combo=validCombo(f4)?f4:comboFromFields(f1,f2,f3);
                String date=digits8(o.optString("date",""));
                if(idx<=0 || !validCombo(combo) || round<1 || round>480)continue;
                Result r=new Result(); r.idx=idx; r.date=date; r.round=round; r.combo=combo; out.add(r);
            }
            out.sort((a,b)->Long.compare(b.idx,a.idx));
            if(out.isEmpty())throw new Exception("유효 결과 0개 · API 원본 "+arr.length()+"개");
            return out;
        }catch(ArrayIndexOutOfBoundsException | StringIndexOutOfBoundsException e){
            throw new Exception("API 처리 중 인덱스 오류 방어됨 · "+e.getClass().getSimpleName()+" · "+String.valueOf(e.getMessage()),e);
        }finally{ if(c!=null)c.disconnect(); }
    }

    private static int safeInt(JSONObject o,String k){
        try{Object v=o.opt(k); if(v instanceof Number)return ((Number)v).intValue(); return Integer.parseInt(String.valueOf(v).trim());}catch(Throwable e){return 0;}
    }
    private static long safeLong(JSONObject o,String k){
        try{Object v=o.opt(k); if(v instanceof Number)return ((Number)v).longValue(); return Long.parseLong(String.valueOf(v).trim());}catch(Throwable e){return 0;}
    }
    private static String digits8(String s){String d=String.valueOf(s==null?"":s).replaceAll("\\D","");return d.length()>=8?d.substring(0,8):d;}
    private static String shortText(String s){String x=String.valueOf(s==null?"":s).replaceAll("\\s+"," ");return x.length()>120?x.substring(0,120)+"…":x;}
    private static boolean validCombo(int c){return c>=1&&c<=4;}
    private static int comboFromFields(int f1,int f2,int f3){
        if(f1==1&&f2==1&&f3==2)return 1;
        if(f1==1&&f2==2&&f3==1)return 2;
        if(f1==2&&f2==1&&f3==1)return 3;
        if(f1==2&&f2==2&&f3==2)return 4;
        return 0;
    }

    public static List<Result> load(Context c){
        List<Result> out=new ArrayList<>(); String raw=prefs(c).getString(K_HISTORY,""); if(raw==null||raw.isEmpty())return out;
        try{
            JSONArray a=new JSONArray(raw);
            for(int i=0;i<a.length();i++){
                JSONObject j=a.optJSONObject(i); if(j==null)continue;
                Result r=new Result(); r.idx=j.optLong("i"); r.date=j.optString("d",""); r.round=j.optInt("r",0); r.combo=j.optInt("c",0);
                if(validResult(r))out.add(r);
            }
        }catch(Exception ignored){}
        out.sort((a,b)->Long.compare(b.idx,a.idx)); if(out.size()>WINDOW)out=new ArrayList<>(out.subList(0,WINDOW)); return out;
    }

    public static void save(Context c,List<Result> list){
        try{
            JSONArray a=new JSONArray(); int n=Math.min(WINDOW,list.size());
            for(int i=0;i<n;i++){ Result r=list.get(i); if(!validResult(r))continue; JSONObject o=new JSONObject(); o.put("i",r.idx);o.put("d",r.date);o.put("r",r.round);o.put("c",r.combo);a.put(o); }
            prefs(c).edit().putString(K_HISTORY,a.toString()).apply();
        }catch(Exception ignored){}
    }

    public static SyncResult sync(Context c)throws Exception{
        SharedPreferences sp=prefs(c);
        String stage="로컬 데이터 읽기";
        try{
            sp.edit().putString(K_LAST_STAGE,stage).apply();
            List<Result> before=load(c); long latestBefore=before.isEmpty()?-1:before.get(0).idx;
            stage="BEPICK API 조회"; sp.edit().putString(K_LAST_STAGE,stage).apply();
            List<Result> api=fetch();
            stage="결과 병합"; sp.edit().putString(K_LAST_STAGE,stage).apply();
            TreeMap<Long,Result> map=new TreeMap<>(Collections.reverseOrder()); for(Result r:before)if(validResult(r))map.put(r.idx,r); for(Result r:api)if(validResult(r))map.put(r.idx,r);
            List<Result> merged=new ArrayList<>(map.values()); if(merged.size()>WINDOW)merged=new ArrayList<>(merged.subList(0,WINDOW));
            if(merged.isEmpty())throw new Exception("병합 후 유효 데이터 0개");
            stage="이전 픽 채점"; sp.edit().putString(K_LAST_STAGE,stage).apply();
            boolean resolved=resolvePending(c,merged);
            stage="로컬 저장"; sp.edit().putString(K_LAST_STAGE,stage).apply(); save(c,merged);
            stage="TARGET 70 규칙 합성/검증"; sp.edit().putString(K_LAST_STAGE,stage).apply(); Analysis a=analyze(merged);
            stage="다음 픽 저장"; sp.edit().putString(K_LAST_STAGE,stage).apply(); savePending(c,merged,a);
            sp.edit().putLong(K_LAST_SYNC,System.currentTimeMillis()).putInt(K_LAST_API_COUNT,api.size()).putString(K_LAST_STAGE,"완료").apply();
            SyncResult sr=new SyncResult(); sr.newRoundResolved=resolved||(!merged.isEmpty()&&merged.get(0).idx!=latestBefore); sr.analysis=a; sr.history=merged; return sr;
        }catch(Throwable e){
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new Exception(stage+" 실패 · "+safeErr(e),e);
        }
    }

    private static String safeErr(Throwable e){String m=e==null?null:e.getMessage();return (m==null||m.trim().isEmpty())?(e==null?"알 수 없는 오류":e.getClass().getSimpleName()):m;}
    private static boolean validResult(Result r){return r!=null&&r.idx>0&&r.round>=1&&r.round<=480&&validCombo(r.combo);}

    public static Analysis analyze(List<Result> desc){
        if(desc==null||desc.isEmpty())return null;
        // 앱 시작 직후/동기화 도중의 부분 데이터도 안전하게 처리한다.
        // 유효 항목만 남기고 idx 중복 제거 후 시간순으로 정렬한다.
        TreeMap<Long,Result> uniq=new TreeMap<>();
        for(Result r:desc)if(validResult(r))uniq.put(r.idx,r);
        if(uniq.isEmpty())return null;
        List<Result> all=new ArrayList<>(uniq.values());
        if(all.size()>WINDOW)all=new ArrayList<>(all.subList(all.size()-WINDOW,all.size()));

        Analysis a;
        if(all.size()<6)a=emptyAnalysis("표본 "+all.size()+"/6 · 안전 수집중");
        else try{ a=decision(all,0,all.size()); }
        catch(Throwable e){ a=emptyAnalysis("현재 분석 보호모드 · "+safeErr(e)); }
        a.count=all.size();
        Result last=all.get(all.size()-1);
        try{a.date=dayKey(last.date);}catch(Throwable ignored){a.date=String.valueOf(last.date==null?"":last.date);}
        try{a.windowRange=rangeLabel(all,0,all.size());}catch(Throwable ignored){a.windowRange="-";}
        try{a.suffix=suffixText(all,all.size(),8);}catch(Throwable ignored){a.suffix="-";}
        try{a.backtest=all.size()>=16?backtest(all):new Backtest();}catch(Throwable ignored){a.backtest=new Backtest();}
        try{a.target70=Target70Engine.optimize(all);}catch(Throwable e){a.target70=new Target70Engine.Result();a.target70.detail="70% 합성엔진 보호모드 · "+safeErr(e);}
        if(a.target70!=null && a.target70.certified){
            a.bestDim=a.target70.dim; a.bestPick=a.target70.pick; a.bestConfidence=a.target70.validationRate; a.bestStrong=true;
            a.bestLabel=DIM[a.bestDim]+" · "+sideLabel(DIM[a.bestDim],a.bestPick);
        }else{
            a.bestDim=-1; a.bestPick=0; a.bestConfidence=0.0; a.bestStrong=false; a.bestLabel="70% 목표 조합 생성 중";
        }
        return a;
    }

    private static Analysis emptyAnalysis(String reason){
        Analysis a=new Analysis(); a.dims=new DimensionStat[3];
        for(int dim=0;dim<3;dim++){
            DimensionStat ds=new DimensionStat(); ds.name=DIM[dim]; ds.pick=0; ds.confidence=0.5; ds.qualified=false;
            ds.markov=neutralEngine("Markov",reason); ds.hmm=neutralEngine("HMM",reason); ds.shape=neutralEngine("Shape AI",reason);
            ds.main3=neutralShape(3); ds.confirm4=neutralShape(4); ds.assist5=neutralShape(5); ds.verdict=reason; a.dims[dim]=ds;
        }
        a.bestDim=-1;a.bestPick=0;a.bestConfidence=0.5;a.bestStrong=false;a.bestLabel="표본 수집중";return a;
    }
    private static EngineStat neutralEngine(String name,String detail){EngineStat e=new EngineStat();e.name=name;e.pick=0;e.pPlus=0.5;e.confidence=0.5;e.samples=0;e.detail=detail;return e;}
    private static ShapeStat neutralShape(int len){ShapeStat s=new ShapeStat();s.length=len;s.pick=0;s.pPlus=0.5;s.confidence=0.5;s.shape="표본 수집중";s.tendency="중립 50.0%";return s;}

    private static Analysis decision(List<Result> all,int start,int end){
        Analysis a=new Analysis(); a.dims=new DimensionStat[3];
        for(int dim=0;dim<3;dim++)a.dims[dim]=dimensionDecision(all,start,end,dim);
        for(int dim=0;dim<3;dim++){
            DimensionStat ds=a.dims[dim];
            if(ds.pick!=0 && (a.bestDim<0 || ds.confidence>a.bestConfidence+1e-12)){
                a.bestDim=dim; a.bestPick=ds.pick; a.bestConfidence=ds.confidence; a.bestStrong=ds.qualified;
            }
        }
        if(a.bestDim>=0)a.bestLabel=DIM[a.bestDim]+" · "+sideLabel(DIM[a.bestDim],a.bestPick);
        else { a.bestLabel="표본 수집중"; a.bestPick=0; a.bestConfidence=0.5; a.bestStrong=false; }
        return a;
    }

    private static DimensionStat dimensionDecision(List<Result> all,int start,int end,int dim){
        DimensionStat ds=new DimensionStat(); ds.name=DIM[dim];
        int n=Math.max(0,end-start);
        // 부분 데이터에서는 엔진을 억지로 실행하지 않고 안전한 중립값으로 둔다.
        if(n<2)ds.markov=neutralEngine("Markov","표본 "+n+"회 · 최소 2회 필요");
        else try{ds.markov=markovStat(all,start,end,dim);}catch(Throwable e){ds.markov=neutralEngine("Markov","오류 격리 · "+safeErr(e));}
        if(n<4)ds.hmm=neutralEngine("HMM","표본 "+n+"회 · 최소 4회 필요");
        else try{ds.hmm=hmmStat(all,start,end,dim);}catch(Throwable e){ds.hmm=neutralEngine("HMM","오류 격리 · "+safeErr(e));}
        try{ds.main3=shapeStat(all,start,end,3,dim);}catch(Throwable e){ds.main3=neutralShape(3);}
        try{ds.confirm4=shapeStat(all,start,end,4,dim);}catch(Throwable e){ds.confirm4=neutralShape(4);}
        try{ds.assist5=shapeStat(all,start,end,5,dim);}catch(Throwable e){ds.assist5=neutralShape(5);}
        try{ds.shape=shapeEngine(ds.main3,ds.confirm4,ds.assist5,dim);}catch(Throwable e){ds.shape=neutralEngine("Shape AI","오류 격리 · "+safeErr(e));}

        EngineStat[] e={ds.markov,ds.hmm,ds.shape};
        int plus=0,minus=0; double p=0;
        for(EngineStat x:e){ if(x.pick>0)plus++; else if(x.pick<0)minus++; p+=x.pPlus; }
        double avg=p/3.0;
        if(plus>minus)ds.pick=+1; else if(minus>plus)ds.pick=-1; else ds.pick=avg>0.5?+1:avg<0.5?-1:0;
        if(ds.pick==0){ ds.confidence=0.5; ds.qualified=false; ds.verdict="3엔진 동률 · 표본 추가 필요"; return ds; }
        double support=0;
        for(EngineStat x:e)support+=supportFor(x.pPlus,ds.pick);
        ds.confidence=support/3.0;
        ds.qualified=ds.confidence>=PICK_THRESHOLD;
        ds.verdict="동등 1표씩 · "+sideLabel(ds.name,+1)+" "+plus+"표 / "+sideLabel(ds.name,-1)+" "+minus+"표 · "+(ds.qualified?"강신호":"일반신호");
        return ds;
    }

    private static double supportFor(double pPlus,int pick){ return pick>0?pPlus:1.0-pPlus; }

    // Variable-order Markov. order-2 표본이 있으면 사용하고, 부족하면 order-1, 이후 전체빈도로 내려간다.
    // 각 관측은 동일하게 1건으로 계산하며 시간감쇠/성과가중은 사용하지 않는다.
    private static EngineStat markovStat(List<Result>a,int start,int end,int dim){
        EngineStat st=new EngineStat(); st.name="Markov";
        int n=end-start; if(n<2){st.detail="표본 부족";return st;}
        int plus=0,minus=0,samples=0,order=0;
        if(n>=3){
            int p1=vec(a.get(end-2).combo,dim),p2=vec(a.get(end-1).combo,dim);
            for(int i=start+2;i<end;i++)if(vec(a.get(i-2).combo,dim)==p1&&vec(a.get(i-1).combo,dim)==p2){int v=vec(a.get(i).combo,dim);if(v>0)plus++;else minus++;samples++;}
            if(samples>0)order=2;
        }
        if(samples==0){
            int last=vec(a.get(end-1).combo,dim);
            for(int i=start+1;i<end;i++)if(vec(a.get(i-1).combo,dim)==last){int v=vec(a.get(i).combo,dim);if(v>0)plus++;else minus++;samples++;}
            if(samples>0)order=1;
        }
        if(samples==0){
            for(int i=start;i<end;i++){int v=vec(a.get(i).combo,dim);if(v>0)plus++;else minus++;samples++;}
            order=0;
        }
        st.samples=samples; st.pPlus=(plus+1.0)/(samples+2.0); st.pick=st.pPlus>0.5?+1:st.pPlus<0.5?-1:0; st.confidence=Math.max(st.pPlus,1-st.pPlus);
        st.detail=(order==2?"order-2":order==1?"order-1":"전체빈도")+" · 표본 "+samples+" · + "+plus+" / - "+minus;
        return st;
    }

    // 2-state HMM (Baum-Welch). 내부 상태 추정만 수행하며 다른 엔진보다 더 큰 표를 주지 않는다.
    private static EngineStat hmmStat(List<Result>a,int start,int end,int dim){
        EngineStat st=new EngineStat(); st.name="HMM"; int n=end-start; st.samples=n;
        if(n<4){st.detail="표본 부족";return st;}
        int[] o=new int[n]; for(int i=0;i<n;i++)o[i]=vec(a.get(start+i).combo,dim)>0?1:0;
        double[] pi={0.5,0.5};
        double[][] A={{0.72,0.28},{0.28,0.72}};
        double[][] B={{0.70,0.30},{0.30,0.70}}; // state0는 -, state1은 + 선호
        final double EPS=1e-6;
        for(int it=0;it<8;it++){
            double[][] alpha=new double[n][2],beta=new double[n][2]; double[] scale=new double[n];
            double z=0; for(int i=0;i<2;i++){alpha[0][i]=pi[i]*B[i][o[0]];z+=alpha[0][i];} scale[0]=Math.max(EPS,z);for(int i=0;i<2;i++)alpha[0][i]/=scale[0];
            for(int t=1;t<n;t++){z=0;for(int j=0;j<2;j++){double q=0;for(int i=0;i<2;i++)q+=alpha[t-1][i]*A[i][j];alpha[t][j]=q*B[j][o[t]];z+=alpha[t][j];}scale[t]=Math.max(EPS,z);for(int j=0;j<2;j++)alpha[t][j]/=scale[t];}
            beta[n-1][0]=beta[n-1][1]=1.0;
            for(int t=n-2;t>=0;t--)for(int i=0;i<2;i++){double q=0;for(int j=0;j<2;j++)q+=A[i][j]*B[j][o[t+1]]*beta[t+1][j];beta[t][i]=q/Math.max(EPS,scale[t+1]);}
            double[][] gamma=new double[n][2]; double[][][] xi=new double[Math.max(1,n-1)][2][2];
            for(int t=0;t<n;t++){z=0;for(int i=0;i<2;i++){gamma[t][i]=alpha[t][i]*beta[t][i];z+=gamma[t][i];}z=Math.max(EPS,z);for(int i=0;i<2;i++)gamma[t][i]/=z;}
            for(int t=0;t<n-1;t++){z=0;for(int i=0;i<2;i++)for(int j=0;j<2;j++){xi[t][i][j]=alpha[t][i]*A[i][j]*B[j][o[t+1]]*beta[t+1][j];z+=xi[t][i][j];}z=Math.max(EPS,z);for(int i=0;i<2;i++)for(int j=0;j<2;j++)xi[t][i][j]/=z;}
            pi[0]=gamma[0][0];pi[1]=gamma[0][1];
            for(int i=0;i<2;i++){double den=0;for(int t=0;t<n-1;t++)den+=gamma[t][i];for(int j=0;j<2;j++){double num=0;for(int t=0;t<n-1;t++)num+=xi[t][i][j];A[i][j]=(num+0.05)/(den+0.10);}}
            for(int i=0;i<2;i++){double den=0,num1=0;for(int t=0;t<n;t++){den+=gamma[t][i];if(o[t]==1)num1+=gamma[t][i];}double p1=(num1+0.05)/(den+0.10);B[i][1]=Math.min(1-EPS,Math.max(EPS,p1));B[i][0]=1-B[i][1];}
        }
        // 마지막 필터링 분포 재계산 후 한 스텝 예측
        double[] cur={pi[0]*B[0][o[0]],pi[1]*B[1][o[0]]}; normalize2(cur);
        for(int t=1;t<n;t++){double[] nx={(cur[0]*A[0][0]+cur[1]*A[1][0])*B[0][o[t]],(cur[0]*A[0][1]+cur[1]*A[1][1])*B[1][o[t]]};normalize2(nx);cur=nx;}
        double ns0=cur[0]*A[0][0]+cur[1]*A[1][0], ns1=cur[0]*A[0][1]+cur[1]*A[1][1];
        st.pPlus=ns0*B[0][1]+ns1*B[1][1]; st.pPlus=Math.min(0.999,Math.max(0.001,st.pPlus)); st.pick=st.pPlus>0.5?+1:st.pPlus<0.5?-1:0;st.confidence=Math.max(st.pPlus,1-st.pPlus);
        st.detail="2상태 · 8회 학습 · 표본 "+n;
        return st;
    }

    private static void normalize2(double[] x){double s=x[0]+x[1];if(!(s>0)||Double.isNaN(s)){x[0]=x[1]=0.5;}else{x[0]/=s;x[1]/=s;}}

    private static EngineStat shapeEngine(ShapeStat s3,ShapeStat s4,ShapeStat s5,int dim){
        EngineStat st=new EngineStat(); st.name="Shape AI"; ShapeStat[] ss={s3,s4,s5}; double p=0;int sm=0;
        for(ShapeStat s:ss){p+=s.pPlus;sm+=s.exactMatches+s.nearMatches;} st.pPlus=p/3.0;st.samples=sm;st.pick=st.pPlus>0.5?+1:st.pPlus<0.5?-1:0;st.confidence=Math.max(st.pPlus,1-st.pPlus);
        st.detail="3·4·5칸 동일 · 일치표본 "+sm; return st;
    }

    // 결과값 자체가 아니라 '같은 값 유지 / 반전' 전이 모양을 비교한다.
    // 완전일치와 Hamming 1 차이 유사일치를 모두 1건으로 동일 취급한다.
    private static ShapeStat shapeStat(List<Result>a,int start,int end,int len,int dim){
        ShapeStat st=new ShapeStat(); st.length=len;
        if(end-start<=len){ st.confidence=0.5; return st; }
        int[] cur=new int[len]; for(int j=0;j<len;j++)cur[j]=vec(a.get(end-len+j).combo,dim);
        int[] curRel=relations(cur); st.shape=shapeLabel(curRel);
        double same=0,flip=0; int exact=0,near=0;
        for(int next=start+len;next<end;next++){
            int[] old=new int[len]; for(int j=0;j<len;j++)old[j]=vec(a.get(next-len+j).combo,dim);
            int[] rel=relations(old); int hd=hamming(curRel,rel);
            if(hd==0)exact++; else if(hd==1)near++; else continue;
            int last=old[len-1], nxt=vec(a.get(next).combo,dim); if(nxt==last)same+=1.0; else flip+=1.0;
        }
        st.exactMatches=exact; st.nearMatches=near; st.sameWeight=same; st.flipWeight=flip; st.effectiveMatches=same+flip; st.ready=st.effectiveMatches>0;
        double pSame=(same+1.0)/(same+flip+2.0); int last=cur[len-1]; st.pPlus=last>0?pSame:1.0-pSame;st.pick=st.pPlus>0.5?+1:st.pPlus<0.5?-1:0;st.confidence=Math.max(st.pPlus,1-st.pPlus);
        st.tendency=pSame>0.5?"연장 "+pct(pSame):pSame<0.5?"꺾임 "+pct(1-pSame):"중립 50.0%";
        return st;
    }

    private static int[] relations(int[] v){ int[] r=new int[v.length-1]; for(int i=1;i<v.length;i++)r[i]=v[i]==v[i-1]?+1:-1; return r; }
    private static int hamming(int[]a,int[]b){ if(a==null||b==null)return 999; int n=Math.abs(a.length-b.length),m=Math.min(a.length,b.length); for(int i=0;i<m;i++)if(a[i]!=b[i])n++; return n; }
    private static int vec(int combo,int dim){
        if(dim<0||dim>2)return 0;
        switch(combo){
            case 1:return dim==0?+1:dim==1?+1:-1;
            case 2:return dim==0?+1:dim==1?-1:+1;
            case 3:return dim==0?-1:dim==1?+1:+1;
            case 4:return -1;
            default:return 0;
        }
    }
    private static String shapeLabel(int[] rel){ StringBuilder sb=new StringBuilder(); for(int i=0;i<rel.length;i++){ if(i>0)sb.append("→"); sb.append(rel[i]>0?"유지":"반전"); } return sb.toString(); }

    private static Backtest backtest(List<Result> all){
        Backtest b=new Backtest();
        // 검증과 동일: 최소 15회 학습 후 이후 회차를 하나씩 가리고 순차 예측한다.
        for(int t=15;t<all.size();t++){
            Analysis a=decision(all,0,t); int actualCombo=all.get(t).combo;
            for(int dim=0;dim<3;dim++){
                DimensionStat ds=a.dims[dim]; if(ds.pick==0)continue; b.dimN[dim]++; if(ds.pick==vec(actualCombo,dim))b.dimHit[dim]++;
                if(ds.qualified){b.strongDimN[dim]++;if(ds.pick==vec(actualCombo,dim))b.strongDimHit[dim]++;}
            }
            if(a.bestDim>=0){ b.globalN++; boolean ok=a.bestPick==vec(actualCombo,a.bestDim);if(ok)b.globalHit++;if(a.bestConfidence>=PICK_THRESHOLD){b.strongN++;if(ok)b.strongHit++;} }
        }
        return b;
    }

    private static void savePending(Context c,List<Result>d,Analysis a){
        if(d.isEmpty()||a==null||a.bestDim<0||a.bestPick==0)return;
        SharedPreferences sp=prefs(c); long next=nextIdx(d.get(0)); long existing=sp.getLong(K_PENDING_IDX,-1); if(existing==next)return;
        int stake=Math.max(5000,sp.getInt(K_BASE_STAKE,5000)); double odds=Math.max(1.01,sp.getFloat(K_ODDS,1.95f));
        String pick=a.bestLabel+" · "+pct(a.bestConfidence);
        sp.edit().putLong(K_PENDING_IDX,next).putInt(K_PENDING_DIM,a.bestDim).putInt(K_PENDING_PICK,a.bestPick)
                .putFloat(K_PENDING_CONF,(float)a.bestConfidence).putInt(K_PENDING_STAKE,stake).putFloat(K_PENDING_ODDS,(float)odds)
                .putString(K_LAST_PICK,pick).putFloat(K_LAST_CONF,(float)a.bestConfidence).apply();
    }

    private static boolean resolvePending(Context c,List<Result>d){
        SharedPreferences sp=prefs(c); long idx=sp.getLong(K_PENDING_IDX,-1); int dim=sp.getInt(K_PENDING_DIM,-1),pick=sp.getInt(K_PENDING_PICK,0);
        if(idx<=0||dim<0||dim>2||pick==0)return false;
        Result actual=null; for(Result r:d)if(r.idx==idx){actual=r;break;} if(actual==null)return false;
        boolean ok=vec(actual.combo,dim)==pick; int st=sp.getInt(K_PENDING_STAKE,5000); double o=sp.getFloat(K_PENDING_ODDS,1.95f); double pnl=ok?st*(o-1.0):-st;
        int n=sp.getInt(K_LIVE_TOTAL,0)+1,hit=sp.getInt(K_LIVE_SUCCESS,0)+(ok?1:0); double old=Double.longBitsToDouble(sp.getLong(K_LIVE_PROFIT,Double.doubleToLongBits(0)));
        appendRecord(c,idx,dim,pick,sp.getFloat(K_PENDING_CONF,0.5f),actual.combo,ok,pnl);
        sp.edit().putInt(K_LIVE_TOTAL,n).putInt(K_LIVE_SUCCESS,hit).putLong(K_LIVE_PROFIT,Double.doubleToLongBits(old+pnl))
                .remove(K_PENDING_IDX).remove(K_PENDING_DIM).remove(K_PENDING_PICK).remove(K_PENDING_CONF).remove(K_PENDING_STAKE).remove(K_PENDING_ODDS).apply();
        return true;
    }

    private static void appendRecord(Context c,long idx,int dim,int pick,double conf,int actual,boolean ok,double pnl){
        try{
            SharedPreferences sp=prefs(c); JSONArray a=new JSONArray(sp.getString(K_RECORDS,"[]")); JSONObject o=new JSONObject();
            o.put("idx",idx);o.put("dim",dim);o.put("pick",pick);o.put("conf",conf);o.put("actual",actual);o.put("ok",ok);o.put("pnl",pnl);a.put(o);
            JSONArray out=new JSONArray();for(int i=Math.max(0,a.length()-1500);i<a.length();i++)out.put(a.get(i)); sp.edit().putString(K_RECORDS,out.toString()).apply();
        }catch(Exception ignored){}
    }

    public static void resetPerformance(Context c){
        prefs(c).edit().remove(K_RECORDS).remove(K_PENDING_IDX).remove(K_PENDING_DIM).remove(K_PENDING_PICK).remove(K_PENDING_CONF)
                .remove(K_PENDING_STAKE).remove(K_PENDING_ODDS).remove(K_LIVE_TOTAL).remove(K_LIVE_SUCCESS).remove(K_LIVE_PROFIT).apply();
    }
    public static void resetAll(Context c){ prefs(c).edit().clear().putBoolean(K_AUTO,false).putInt(K_BASE_STAKE,5000).putFloat(K_ODDS,1.95f).apply(); }

    public static JSONObject backup(Context c)throws Exception{
        SharedPreferences sp=prefs(c); JSONObject root=new JSONObject(); root.put("format","BubbleTarget70V12Backup");
        root.put("history",new JSONArray(sp.getString(K_HISTORY,"[]"))); root.put("records",new JSONArray(sp.getString(K_RECORDS,"[]")));
        JSONObject st=new JSONObject(); st.put("liveTotal",sp.getInt(K_LIVE_TOTAL,0));st.put("liveHit",sp.getInt(K_LIVE_SUCCESS,0));st.put("liveProfit",sp.getLong(K_LIVE_PROFIT,Double.doubleToLongBits(0)));
        st.put("stake",sp.getInt(K_BASE_STAKE,5000));st.put("odds",sp.getFloat(K_ODDS,1.95f));st.put("auto",sp.getBoolean(K_AUTO,false));root.put("state",st); return root;
    }
    public static void restore(Context c,JSONObject root)throws Exception{
        SharedPreferences.Editor ed=prefs(c).edit(); JSONArray src=root.optJSONArray("history");
        if(src!=null){ TreeMap<Long,JSONObject> map=new TreeMap<>(Collections.reverseOrder()); for(int i=0;i<src.length();i++){JSONObject o=src.optJSONObject(i);if(o!=null){long idx=o.optLong("i",o.optLong("idx",0));if(idx>0)map.put(idx,o);}}
            JSONArray cut=new JSONArray();int n=0;for(JSONObject o:map.values()){if(n++>=WINDOW)break;cut.put(o);}ed.putString(K_HISTORY,cut.toString()); }
        if(root.has("records"))ed.putString(K_RECORDS,root.getJSONArray("records").toString()); JSONObject st=root.optJSONObject("state");
        if(st!=null){ed.putInt(K_LIVE_TOTAL,st.optInt("liveTotal",0));ed.putInt(K_LIVE_SUCCESS,st.optInt("liveHit",0));ed.putLong(K_LIVE_PROFIT,st.optLong("liveProfit",Double.doubleToLongBits(0)));ed.putInt(K_BASE_STAKE,Math.max(5000,st.optInt("stake",5000)));ed.putFloat(K_ODDS,(float)st.optDouble("odds",1.95));ed.putBoolean(K_AUTO,false);}
        ed.apply();
    }

    private static List<Result> chronoAsc(List<Result>desc){List<Result>copy=new ArrayList<>(desc);copy.sort(Comparator.comparingLong(x->x.idx));return copy;}
    public static List<Result> recentDesc(List<Result>desc,int limit){List<Result>copy=new ArrayList<>(desc);copy.sort((a,b)->Long.compare(b.idx,a.idx));return copy.size()>limit?new ArrayList<>(copy.subList(0,limit)):copy;}
    private static String dayKey(String s){String digits=String.valueOf(s==null?"":s).replaceAll("\\D","");return digits.length()>=8?digits.substring(0,8):String.valueOf(s==null?"":s);}
    private static String suffixText(List<Result>a,int end,int max){int from=Math.max(0,end-max);StringBuilder sb=new StringBuilder();for(int i=from;i<end;i++){if(sb.length()>0)sb.append(" → ");sb.append(COMBO[a.get(i).combo]);}return sb.toString();}
    private static String rangeLabel(List<Result>a,int start,int end){if(end<=start)return "-";Result first=a.get(start),last=a.get(end-1);return first.date+" "+first.round+"회 → "+last.date+" "+last.round+"회";}
    public static String sideLabel(String dim,int v){if("좌/우".equals(dim))return v>0?"좌":"우";if("사다리수".equals(dim))return v>0?"3줄":"4줄";return v>0?"홀":"짝";}
    public static long nextIdx(Result r){try{String dk=dayKey(r.date);if(r.round<480)return Long.parseLong(dk.substring(2,8)+String.format(Locale.US,"%04d",r.round+1));SimpleDateFormat f=new SimpleDateFormat("yyyyMMdd",Locale.US);Calendar c=Calendar.getInstance();c.setTime(f.parse(dk));c.add(Calendar.DAY_OF_MONTH,1);String d=f.format(c.getTime());return Long.parseLong(d.substring(2,8)+"0001");}catch(Exception e){return r.idx+1;}}
    public static long millisToNextDraw(){long interval=180000L,now=System.currentTimeMillis();long mod=Math.floorMod(now,interval);long left=interval-mod;return left==0?interval:left;}
    public static String countdownText(){long s=(millisToNextDraw()+999)/1000;return String.format(Locale.KOREA,"%02d:%02d",s/60,s%60);}
    public static String pct(double v){return String.format(Locale.KOREA,"%.1f%%",v*100);}
    public static String money(double v){return String.format(Locale.KOREA,"%,.0f원",v);}
    public static String signed(double v){return (v>=0?"+":"")+money(v);}
    public static String liveRate(Context c){SharedPreferences sp=prefs(c);int n=sp.getInt(K_LIVE_TOTAL,0),h=sp.getInt(K_LIVE_SUCCESS,0);return n==0?"-":h+"/"+n+" ("+pct((double)h/n)+")";}
}
