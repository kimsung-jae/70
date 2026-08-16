package com.bubbleladder.mhsunweightedv1;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity{
    private static final int REQ_EXPORT=6201,REQ_IMPORT=6202,REQ_NOTI=6203;
    private final Handler h=new Handler(Looper.getMainLooper()); private final ExecutorService ex=Executors.newSingleThreadExecutor();
    private boolean suppressAutoToggle=false, pendingAutoStart=false, destroyed=false;
    private TextView countdown,bgState,status,nextRound,bestPick,bestConf,dimSummary,patternDetail,backtest,live,profit,recent;
    private EditText stake,odds; private CheckBox background; private Button refresh,saveSetting,backup,restore,resetStats,resetAll;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){reloadAsync();}};
    private final Runnable countdownTask=new Runnable(){@Override public void run(){if(countdown!=null)countdown.setText(FlowCore.countdownText());h.postDelayed(this,1000L);}};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        setContentView(buildUi());
        loadSettings();
        bindActions();
        registerUpdates();
        h.post(countdownTask);
        // V2.2 안전 시작: 앱을 먼저 정상 실행하고, 백그라운드는 사용자가 직접 켠 뒤 시작한다.
        reloadAsync();
    }

    private View buildUi(){
        ScrollView sv=new ScrollView(this);sv.setFillViewport(true);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(14),dp(16),dp(14),dp(30));root.setBackgroundColor(Color.rgb(7,19,26));sv.addView(root);
        root.addView(tv("보글사다리 · 삼치기90 AUTO FIND V1.4",24,Color.WHITE,true));TextView sub=tv("PASS 없음 · 4개 조합 중 제외 1개 탐색 · 나머지 3개를 매회 추천",12,Color.rgb(110,231,183),false);sub.setPadding(0,dp(4),0,dp(14));root.addView(sub);
        LinearLayout clock=card();clock.addView(tv("다음 추첨까지",12,Color.rgb(148,163,184),false));countdown=tv("--:--",38,Color.rgb(56,189,248),true);clock.addView(countdown);bgState=tv("백그라운드 상태 확인 중",12,Color.rgb(203,213,225),false);bgState.setPadding(0,dp(4),0,0);clock.addView(bgState);root.addView(clock);
        LinearLayout ctrl=card();refresh=button("🔄 지금 수집 / META90 1픽 생성",Color.rgb(5,150,105));ctrl.addView(refresh,new LinearLayout.LayoutParams(-1,dp(54)));background=new CheckBox(this);background.setText("백그라운드 자동추첨 ON");background.setTextColor(Color.WHITE);background.setTextSize(15);background.setPadding(0,dp(8),0,0);ctrl.addView(background);status=tv("조회 준비",12,Color.rgb(203,213,225),false);status.setPadding(0,dp(6),0,0);ctrl.addView(status);root.addView(ctrl);
        LinearLayout hero=card();hero.addView(tv("다음 회차 · 삼치기 추천 3픽",12,Color.GRAY,false));nextRound=tv("-",15,Color.WHITE,true);hero.addView(nextRound);bestPick=tv("분석 대기",30,Color.rgb(52,211,153),true);bestPick.setPadding(0,dp(7),0,0);hero.addView(bestPick);bestConf=tv("90% 목표 · 매 회차 반드시 삼치기 1픽 생성",15,Color.rgb(253,224,71),true);bestConf.setPadding(0,dp(4),0,0);hero.addView(bestConf);TextView note=tv("META90가 현재 데이터에 맞는 규칙을 자동 선택 · 90+면 인증표시, 미달이어도 추천은 계속",12,Color.rgb(110,231,183),true);note.setPadding(0,dp(10),0,0);hero.addView(note);root.addView(hero);
        LinearLayout dc=card();dc.addView(section("기초 MHS 3엔진 · 진단용"));dimSummary=tv("-",14,Color.WHITE,false);dimSummary.setLineSpacing(0,1.35f);dc.addView(dimSummary);root.addView(dc);
        LinearLayout pc=card();pc.addView(section("삼치기 META90 엔진 상세"));patternDetail=tv("-",13,Color.rgb(226,232,240),false);patternDetail.setLineSpacing(0,1.3f);pc.addView(patternDetail);root.addView(pc);
        LinearLayout bc=card();bc.addView(section("미래누설 없는 삼치기 순차 재현검증"));backtest=tv("-",14,Color.WHITE,false);backtest.setLineSpacing(0,1.3f);bc.addView(backtest);live=tv("실전 기록 · 아직 없음",14,Color.rgb(125,211,252),true);live.setPadding(0,dp(10),0,0);bc.addView(live);root.addView(bc);
        LinearLayout bet=card();bet.addView(section("기준금액 설정 · 삼치기는 승률만 기록"));LinearLayout ir=new LinearLayout(this);ir.setOrientation(LinearLayout.HORIZONTAL);stake=input("5000");stake.setHint("배팅금액");stake.setInputType(InputType.TYPE_CLASS_NUMBER);odds=input("1.95");odds.setHint("배당");odds.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);ir.addView(stake,new LinearLayout.LayoutParams(0,dp(52),1));LinearLayout.LayoutParams olp=new LinearLayout.LayoutParams(0,dp(52),1);olp.setMargins(dp(8),0,0,0);ir.addView(odds,olp);bet.addView(ir);saveSetting=button("설정 저장 · 최소 5,000원",Color.rgb(30,64,175));LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(-1,dp(48));slp.setMargins(0,dp(8),0,0);bet.addView(saveSetting,slp);profit=tv("-",13,Color.rgb(226,232,240),false);profit.setPadding(0,dp(10),0,0);bet.addView(profit);root.addView(bet);
        LinearLayout rc=card();rc.addView(section("최근 15회"));recent=tv("-",14,Color.WHITE,false);recent.setLineSpacing(0,1.25f);rc.addView(recent);root.addView(rc);
        LinearLayout data=card();data.addView(section("백업 / 리셋"));LinearLayout dr=new LinearLayout(this);dr.setOrientation(LinearLayout.HORIZONTAL);backup=button("💾 백업",Color.rgb(21,128,61));restore=button("📂 복원",Color.rgb(109,40,217));dr.addView(backup,new LinearLayout.LayoutParams(0,dp(50),1));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(50),1);rp.setMargins(dp(8),0,0,0);dr.addView(restore,rp);data.addView(dr);resetStats=button("승률/실전성적 초기화",Color.rgb(127,29,29));LinearLayout.LayoutParams r1=new LinearLayout.LayoutParams(-1,dp(46));r1.setMargins(0,dp(8),0,0);data.addView(resetStats,r1);resetAll=button("전체 데이터 초기화",Color.rgb(90,20,20));LinearLayout.LayoutParams r2=new LinearLayout.LayoutParams(-1,dp(46));r2.setMargins(0,dp(8),0,0);data.addView(resetAll,r2);root.addView(data);
        root.addView(tv("※ 표시 %는 미래를 가린 walk-forward 실검증률입니다. META90는 매 회차 1픽을 만들지만 실제 90%가 검증된 경우에만 90+ 인증으로 표시합니다.",11,Color.GRAY,false));return sv;
    }

    private void bindActions(){
        refresh.setOnClickListener(v->manualSync());
        saveSetting.setOnClickListener(v->saveSettings());
        background.setOnCheckedChangeListener((v,on)->{
            if(suppressAutoToggle)return;
            if(on){
                FlowCore.prefs(this).edit().putBoolean(FlowCore.K_AUTO,true).apply();
                if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
                    pendingAutoStart=true;
                    bgState.setText("알림 권한 허용 후 자동추첨 시작");
                    bgState.setTextColor(Color.rgb(253,224,71));
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTI);
                }else startAutoServiceSafely();
            }else{
                pendingAutoStart=false;
                FlowCore.prefs(this).edit().putBoolean(FlowCore.K_AUTO,false).apply();
                stopAutoServiceSafely();
                updateBgState();
            }
        });
        backup.setOnClickListener(v->startExport());restore.setOnClickListener(v->startImport());resetStats.setOnClickListener(v->confirmResetStats());resetAll.setOnClickListener(v->confirmResetAll());
    }
    private void loadSettings(){android.content.SharedPreferences sp=FlowCore.prefs(this);stake.setText(String.valueOf(Math.max(5000,sp.getInt(FlowCore.K_BASE_STAKE,5000))));odds.setText(String.valueOf(sp.getFloat(FlowCore.K_ODDS,1.95f)));suppressAutoToggle=true;background.setChecked(sp.getBoolean(FlowCore.K_AUTO,false));suppressAutoToggle=false;updateBgState();}
    private int readStake(){try{return Math.max(5000,Integer.parseInt(stake.getText().toString().trim()));}catch(Exception e){return 5000;}}
    private double readOdds(){try{return Math.max(1.01,Double.parseDouble(odds.getText().toString().trim()));}catch(Exception e){return 1.95;}}
    private void saveSettings(){int s=readStake();double o=readOdds();FlowCore.prefs(this).edit().putInt(FlowCore.K_BASE_STAKE,s).putFloat(FlowCore.K_ODDS,(float)o).apply();stake.setText(String.valueOf(s));Toast.makeText(this,"설정 저장 완료",Toast.LENGTH_SHORT).show();reloadAsync();}
    private void saveSettingsSilent(){FlowCore.prefs(this).edit().putInt(FlowCore.K_BASE_STAKE,readStake()).putFloat(FlowCore.K_ODDS,(float)readOdds()).apply();}
    private void manualSync(){
        saveSettingsSilent();refresh.setEnabled(false);status.setText("API 수집 + 삼치기90 방법탐색 중...");
        safeExecute(()->{try{FlowCore.SyncResult sr=FlowCore.sync(this);postUi(()->{render(sr.analysis,sr.history);int apiN=FlowCore.prefs(this).getInt(FlowCore.K_LAST_API_COUNT,0);status.setText("● 조회 성공 · API "+apiN+"개 · "+new SimpleDateFormat("HH:mm:ss",Locale.KOREA).format(new Date()));status.setTextColor(Color.rgb(52,211,153));refresh.setEnabled(true);});}
            catch(Throwable e){postUi(()->{String stage=FlowCore.prefs(this).getString(FlowCore.K_LAST_STAGE,"");status.setText("조회 실패"+(stage==null||stage.isEmpty()?"":" ["+stage+"]")+": "+safeMessage(e));status.setTextColor(Color.rgb(248,113,113));refresh.setEnabled(true);});}});
    }
    private void reloadAsync(){safeExecute(()->{try{List<FlowCore.Result>d=FlowCore.load(this);FlowCore.Analysis a=d.isEmpty()?null:FlowCore.analyze(d);postUi(()->{if(a!=null)render(a,d);else status.setText("데이터 없음 · 지금 수집/분석을 눌러주세요.");updateBgState();});}catch(Throwable e){postUi(()->{status.setText("로컬 분석 오류: "+safeMessage(e));status.setTextColor(Color.rgb(248,113,113));});}});}

    private void render(FlowCore.Analysis a,List<FlowCore.Result>d){if(a==null||d==null||d.isEmpty())return;FlowCore.Result last=d.get(0);nextRound.setText(last.round<480?last.date+" · "+(last.round+1)+"회":"다음날 · 1회");
        TriPick90Engine.Result t=a.tri90;
        if(t!=null&&t.excludedCombo>=1&&t.excludedCombo<=4){
            bestPick.setText(t.picksLabel);
            String badge=t.targetAchieved?"🔥 90+ 독립검증 통과":"🎯 META90 1픽";
            bestConf.setText(badge+" · 제외 "+t.excludedLabel+" · 실검증 "+FlowCore.pct(t.validationRate)+(t.validationN>0?" · "+t.validationHit+"/"+t.validationN:" · 초기표본"));
            bestPick.setTextColor(t.targetAchieved?Color.rgb(52,211,153):Color.rgb(253,224,71));
        }else{bestPick.setText("META90 분석 준비");bestConf.setText("데이터 동기화 필요");bestPick.setTextColor(Color.rgb(253,224,71));}
        StringBuilder dsb=new StringBuilder();for(int i=0;i<3;i++){FlowCore.DimensionStat x=a.dims[i];dsb.append(i==0?"":"\n\n").append("● ").append(x.name).append(" → ").append(x.pick==0?"대기":FlowCore.sideLabel(x.name,x.pick)).append(" · 기초 ").append(FlowCore.pct(x.confidence)).append("\n   ").append(x.verdict).append("\n   ").append(x.markov.label(x.name)).append("\n   ").append(x.hmm.label(x.name)).append("\n   ").append(x.shape.label(x.name));}dimSummary.setText(dsb.toString());
        StringBuilder ps=new StringBuilder();if(t==null){ps.append("삼치기90 엔진 준비중");}else{ps.append(t.detail).append("\n\n탐색한 방법/합성규칙: ").append(t.searched).append("개");if(t.excludedCombo>=1&&t.excludedCombo<=4){ps.append("\n추천 3픽: ").append(t.picksLabel).append("\n제외 1픽: ").append(t.excludedLabel).append("\n선택 모드: ").append(t.mode).append("\n사용 규칙: ").append(t.rule).append("\n선택구간: ").append(t.preHit).append("/").append(t.preN).append(" = ").append(FlowCore.pct(t.preRate)).append("\n마지막 holdout: ").append(t.holdHit).append("/").append(t.holdN).append(" = ").append(FlowCore.pct(t.holdRate));}else{ps.append("\n데이터 동기화 후 META90 1픽 생성");}}patternDetail.setText(ps.toString());
        FlowCore.Backtest b=a.backtest;backtest.setText("학습 데이터 "+a.count+"/150회 · "+a.windowRange+"\n현재 최근흐름: "+a.suffix+"\n\n[삼치기90 선택방법 walk-forward]\n"+(t!=null&&t.excludedCombo>=1&&t.excludedCombo<=4?(t.validationN>0?t.validationHit+"/"+t.validationN+" = "+FlowCore.pct(t.validationRate)+"\n선택구간 "+FlowCore.pct(t.preRate)+" · 마지막 holdout "+FlowCore.pct(t.holdRate):"초기표본 · META90 삼치기 추천") : "데이터 동기화 필요")+"\n\n[기초 MHS 참고 재현]\n"+stat(b.globalHit,b.globalN)+"\n\n※ 각 과거 시점에서 미래 결과를 가린 뒤 제외 1개를 순차 재현검증");
        android.content.SharedPreferences sp=FlowCore.prefs(this);int n=sp.getInt(FlowCore.K_LIVE_TOTAL,0),hit=sp.getInt(FlowCore.K_LIVE_SUCCESS,0);live.setText("실전 삼치기 · "+(n>0?hit+"/"+n+" = "+FlowCore.pct((double)hit/n):"아직 없음")+" · 성공=실제 결과가 제외픽이 아니면 적중");int st=Math.max(5000,sp.getInt(FlowCore.K_BASE_STAKE,5000));double o=Math.max(1.01,sp.getFloat(FlowCore.K_ODDS,1.95f));profit.setText("기준금액: "+FlowCore.money(st)+" · 기존 단일픽 배당 설정 "+String.format(Locale.KOREA,"%.2f",o)+"\n삼치기는 추천 3개에 대한 실제 배팅 방식/배당이 별도이므로 이 버전에서는 수익을 임의 계산하지 않고 적중률만 기록합니다.");
        List<FlowCore.Result> td=FlowCore.recentDesc(d,15);StringBuilder rr=new StringBuilder();for(int i=0;i<td.size();i++){FlowCore.Result r=td.get(i);rr.append(i==0?"최신  ":"      ").append(r.round).append("회 · ").append(FlowCore.COMBO[r.combo]);if(i<td.size()-1)rr.append("\n");}recent.setText(rr.length()==0?"결과 없음":rr.toString());}
    private String stat(int h,int n){return n==0?"픽 없음":h+"/"+n+" = "+FlowCore.pct((double)h/n);}
    private void startAutoServiceSafely(){
        try{
            Intent i=new Intent(this,AutoDrawService.class);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
            bgState.setText("● 백그라운드 자동추첨 시작됨");bgState.setTextColor(Color.rgb(52,211,153));
        }catch(Throwable e){
            FlowCore.prefs(this).edit().putBoolean(FlowCore.K_AUTO,false).apply();
            suppressAutoToggle=true;background.setChecked(false);suppressAutoToggle=false;
            bgState.setText("자동추첨 시작 실패 · 앱은 계속 사용 가능");bgState.setTextColor(Color.rgb(248,113,113));
            Toast.makeText(this,"백그라운드 시작 실패: "+safeMessage(e),Toast.LENGTH_LONG).show();
        }
    }
    private void stopAutoServiceSafely(){try{stopService(new Intent(this,AutoDrawService.class));}catch(Throwable ignored){}}
    private void updateBgState(){boolean on=FlowCore.prefs(this).getBoolean(FlowCore.K_AUTO,false);if(bgState!=null){bgState.setText(on?"● 백그라운드 자동추첨 ON":"○ 백그라운드 OFF · 필요할 때 직접 켜기");bgState.setTextColor(on?Color.rgb(52,211,153):Color.GRAY);}}
    private void registerUpdates(){try{IntentFilter f=new IntentFilter(FlowCore.ACTION_UPDATED);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);}catch(Throwable e){status.setText("화면 갱신 수신기 오류: "+safeMessage(e));}}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==REQ_NOTI){
            boolean granted=grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED;
            if(pendingAutoStart&&granted){pendingAutoStart=false;startAutoServiceSafely();}
            else if(pendingAutoStart){pendingAutoStart=false;FlowCore.prefs(this).edit().putBoolean(FlowCore.K_AUTO,false).apply();suppressAutoToggle=true;background.setChecked(false);suppressAutoToggle=false;bgState.setText("알림 권한이 없어 자동추첨 OFF");bgState.setTextColor(Color.rgb(248,113,113));}
        }
    }
    private void safeExecute(Runnable r){try{if(!destroyed)ex.execute(r);}catch(Throwable ignored){}}
    private void postUi(Runnable r){if(destroyed)return;h.post(()->{if(!destroyed&&!isFinishing())try{r.run();}catch(Throwable e){if(status!=null){status.setText("화면 처리 오류: "+safeMessage(e));status.setTextColor(Color.rgb(248,113,113));}}});}
    private String safeMessage(Throwable e){String m=e==null?null:e.getMessage();return (m==null||m.trim().isEmpty())?(e==null?"알 수 없는 오류":e.getClass().getSimpleName()):m;}
    private void startExport(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"BubbleTriPick90V16_"+new SimpleDateFormat("yyyyMMdd_HHmm",Locale.KOREA).format(new Date())+".json");startActivityForResult(i,REQ_EXPORT);}
    private void startImport(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,REQ_IMPORT);}
    @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);if(res!=RESULT_OK||data==null||data.getData()==null)return;Uri u=data.getData();try{if(req==REQ_EXPORT){OutputStream o=getContentResolver().openOutputStream(u);if(o==null)throw new Exception("파일 열기 실패");o.write(FlowCore.backup(this).toString(2).getBytes("UTF-8"));o.close();Toast.makeText(this,"백업 완료",Toast.LENGTH_LONG).show();}else if(req==REQ_IMPORT){InputStream in=getContentResolver().openInputStream(u);if(in==null)throw new Exception("파일 열기 실패");BufferedReader br=new BufferedReader(new InputStreamReader(in,"UTF-8"));StringBuilder sb=new StringBuilder();String line;while((line=br.readLine())!=null)sb.append(line);br.close();FlowCore.restore(this,new JSONObject(sb.toString()));loadSettings();reloadAsync();Toast.makeText(this,"복원 완료",Toast.LENGTH_LONG).show();}}catch(Exception e){Toast.makeText(this,"처리 실패: "+e.getMessage(),Toast.LENGTH_LONG).show();}}
    private void confirmResetStats(){new AlertDialog.Builder(this).setTitle("실전성적 초기화").setMessage("승패·수익 기록만 초기화하고 학습용 Rolling 데이터는 보존합니다.").setNegativeButton("취소",null).setPositiveButton("초기화",(d,w)->{FlowCore.resetPerformance(this);Toast.makeText(this,"성적 초기화 완료",Toast.LENGTH_SHORT).show();reloadAsync();}).show();}
    private void confirmResetAll(){new AlertDialog.Builder(this).setTitle("전체 데이터 초기화").setMessage("학습용 결과·승률·설정까지 모두 초기화합니다. 초기화 후 자동추첨은 OFF 상태로 시작합니다.").setNegativeButton("취소",null).setPositiveButton("전체 초기화",(d,w)->{FlowCore.resetAll(this);loadSettings();Toast.makeText(this,"전체 초기화 완료",Toast.LENGTH_SHORT).show();reloadAsync();}).show();}
    private LinearLayout card(){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);x.setPadding(dp(14),dp(14),dp(14),dp(14));GradientDrawable g=new GradientDrawable();g.setColor(Color.rgb(15,30,46));g.setCornerRadius(dp(18));x.setBackground(g);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(12));x.setLayoutParams(lp);return x;}
    private TextView section(String s){TextView v=tv(s,16,Color.WHITE,true);v.setPadding(0,0,0,dp(10));return v;}
    private TextView tv(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private Button button(String s,int color){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(14);b.setAllCaps(false);GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(12));b.setBackground(g);return b;}
    private EditText input(String s){EditText e=new EditText(this);e.setText(s);e.setTextColor(Color.WHITE);e.setHintTextColor(Color.GRAY);e.setTextSize(16);e.setPadding(dp(12),0,dp(12),0);GradientDrawable g=new GradientDrawable();g.setColor(Color.rgb(30,41,59));g.setCornerRadius(dp(12));e.setBackground(g);return e;}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    @Override protected void onDestroy(){destroyed=true;h.removeCallbacksAndMessages(null);try{unregisterReceiver(receiver);}catch(Throwable ignored){}try{ex.shutdownNow();}catch(Throwable ignored){}super.onDestroy();}
}
