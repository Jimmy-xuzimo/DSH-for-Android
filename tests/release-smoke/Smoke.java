package dsh.qa.settings;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** No Kotlin/AndroidX test runtime: exercises the actual R8-minified APK. */
public class Smoke extends Instrumentation {
  private Activity activity;
  public void onCreate(Bundle args) { super.onCreate(args); start(); }
  private WebView web(View view) {
    if(view instanceof WebView) return (WebView)view;
    if(view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view; for(int i=0;i<group.getChildCount();i++){WebView found=web(group.getChildAt(i));if(found!=null)return found;} }
    return null;
  }
  private String evaluate(String script) throws Exception {
    final String[] result={""};CountDownLatch latch=new CountDownLatch(1);
    runOnMainSync(()->web(activity.getWindow().getDecorView()).evaluateJavascript(script,value->{result[0]=value;latch.countDown();}));
    if(!latch.await(10,TimeUnit.SECONDS))throw new Exception("WebView callback timed out");return result[0];
  }
  private List<AccessibilityNodeInfo> nodes() {
    List<AccessibilityNodeInfo> result=new ArrayList<>();walk(getUiAutomation().getRootInActiveWindow(),result);return result;
  }
  private void walk(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> result) {
    if(node==null)return;result.add(node);for(int i=0;i<node.getChildCount();i++)walk(node.getChild(i),result);
  }
  private void click(Predicate<String> label) throws Exception {
    for(int attempt=0;attempt<300;attempt++) {
      for(AccessibilityNodeInfo node:nodes()) {
        if(node.isVisibleToUser()&&(label.test(String.valueOf(node.getText()))||label.test(String.valueOf(node.getContentDescription())))) {
          AccessibilityNodeInfo target=node;while(target!=null&&!target.isClickable())target=target.getParent();
          if(target!=null&&target.performAction(AccessibilityNodeInfo.ACTION_CLICK)){Thread.sleep(200);return;}
        }
      }Thread.sleep(150);
    }throw new Exception("Required native control was not reachable");
  }
  private void screenshot(String name) throws Exception {
    Bitmap image=getUiAutomation().takeScreenshot();if(image==null)throw new Exception("Screenshot unavailable");
    try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getExternalCacheDir(),"signed-"+name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,output);}image.recycle();
  }
  private AccessibilityNodeInfo slider() throws Exception {
    for(int attempt=0;attempt<300;attempt++){for(AccessibilityNodeInfo node:nodes())if(node.getRangeInfo()!=null&&node.isEnabled()&&node.isVisibleToUser())return node;for(AccessibilityNodeInfo node:nodes())if(node.isScrollable()&&node.isVisibleToUser())node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(150);}throw new Exception("Official slider unavailable");
  }
  private void shell(String command) throws Exception {
    try(android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] buffer=new byte[1024];while(in.read(buffer)!=-1){}}
  }
  private void resizeAndCheck(String size,int density) throws Exception {
    shell("wm size "+size);shell("wm density "+density);Thread.sleep(1200);
    click(value->value.equals("对话")||value.equals("Chats"));
    click(value->value.equals("设置")||value.equals("Settings"));
    // A density change can restore the previous detail. Return to categories
    // when present; wide settings retain their visible category column.
    for(AccessibilityNodeInfo node:nodes())if("返回设置分类".equals(String.valueOf(node.getContentDescription()))||"Back to settings".equals(String.valueOf(node.getContentDescription()))){click(value->value.equals("返回设置分类")||value.equals("Back to settings"));break;}
    click(value->value.equals("外观与交互")||value.equals("Appearance and interaction"));
    if(slider().getRangeInfo()==null)throw new Exception("Native settings lost after window resize");
    sendStatus(0,bundle("window",size+"@"+density+":PASS"));
  }
  private Bundle bundle(String key,String value){Bundle b=new Bundle();b.putString(key,value);return b;}
  private void font(float value) throws Exception {
    Bundle arguments=new Bundle();arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,value);
    if(!slider().performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),arguments))throw new Exception("Official slider rejected input");
    for(int attempt=0;attempt<200;attempt++){if(evaluate("parseInt(document.body.style.getPropertyValue('--dsh-content-font-size'))").equals(String.valueOf((int)value)))return;Thread.sleep(150);}throw new Exception("DSH did not accept the official slider value");
  }
  public void onStart() {
    Bundle result=new Bundle();
    try {
      if(android.os.Build.VERSION.SDK_INT>=33)getUiAutomation().grantRuntimePermission(getTargetContext().getPackageName(),"android.permission.POST_NOTIFICATIONS");
      Intent intent=getTargetContext().getPackageManager().getLaunchIntentForPackage(getTargetContext().getPackageName());intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);activity=startActivitySync(intent);
      boolean ready=false;for(int attempt=0;attempt<1200;attempt++){if(evaluate("Boolean(document.getElementById('root')?.childElementCount&&document.documentElement.getBoundingClientRect().height>100)").equals("true")){ready=true;break;}Thread.sleep(200);}if(!ready)throw new Exception("Production engine did not render");
      click(value->value.equals("设置")||value.equals("Settings"));
      click(value->value.equals("外观与交互")||value.equals("Appearance and interaction"));
      slider();float original=Float.parseFloat(evaluate("parseInt(document.body.style.getPropertyValue('--dsh-content-font-size'))"));
      font(original==17?16:17);screenshot("appearance");font(original);
      click(value->value.equals("返回设置分类")||value.equals("Back to settings"));
      click(value->value.equals("模型")||value.equals("Models"));
      boolean fields=false;for(int attempt=0;attempt<300;attempt++) {
        for(AccessibilityNodeInfo node:nodes())if("android.widget.EditText".contentEquals(node.getClassName())&&node.isVisibleToUser())fields=true;
        if(fields)break;
        boolean edit=false;for(AccessibilityNodeInfo node:nodes()){String text=String.valueOf(node.getText());if(text.startsWith("编辑 ")||text.startsWith("Edit ")){edit=true;break;}}
        if(edit){click(value->value.startsWith("编辑 ")||value.startsWith("Edit "));}Thread.sleep(150);
      }
      if(!fields)throw new Exception("Production model editor did not expose native inputs");
      screenshot("models");
      if(!evaluate("getComputedStyle(document.querySelector('[data-material-settings-controller]')).opacity").equals("\"0\""))throw new Exception("Legacy controller is visible");
      try {resizeAndCheck("1680x2400",320);resizeAndCheck("2160x1600",320);resizeAndCheck("1080x2400",420);}
      finally {shell("wm size reset");shell("wm density reset");}
      result.putString("result","SIGNED_RELEASE_NATIVE_SETTINGS_OK");
      result.putString("version",getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(),0).versionName);
      finish(Activity.RESULT_OK,result);
    }catch(Exception error){result.putString("failure",error.toString());finish(Activity.RESULT_CANCELED,result);}
  }
}
