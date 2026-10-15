package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import java.util.ArrayList;

/** Production dashboard geometry and both reading edges, including narrow rotated safe areas. */
final class ControlBoundaryChecks {
    private final Instrumentation test;
    private int assertions;
    ControlBoundaryChecks(Instrumentation test) { this.test = test; }
    String run(int rotation) {
        Instrumentation.ActivityMonitor monitor = test.addMonitor(MainActivity.class.getName(), null, false);
        Activity first = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        require(test.getUiAutomation().setRotation(rotation), "rotation accepted"); SystemClock.sleep(500); test.waitForIdleSync();
        Activity activity = monitor.getLastActivity() == null ? first : monitor.getLastActivity(); test.removeMonitor(monitor);
        try {
            require(activity.getDisplay().getRotation() == rotation, "actual direction matches request");
            main(() -> {
                int w = activity.getWindow().getDecorView().getWidth(), h = activity.getWindow().getDecorView().getHeight();
                for (int columns : new int[]{3,4,5}) for (boolean narrow : new boolean[]{false,true}) for (String position : new String[]{"auto","side","bottom"}) for (boolean left : new boolean[]{false,true}) check(activity, w, h, columns, narrow, position, left);
            });
            return "PASS: control-boundaries rotation=" + rotation + "; " + assertions + " assertions; real dashboard 3/4/5 columns, full/narrow safe bounds, stable side/bottom choice, nested fades and last-row pixels";
        } finally { main(activity::finish); test.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE); }
    }
    private void check(Activity activity, int w, int h, int columns, boolean narrow, String position, boolean left) {
        Context context = activity; Prefs prefs = new Prefs(context);
        prefs.data.edit().putInt("panel_columns", columns).putInt("panel_density",1).putInt("panel_label_size",1).putString("panel_tools_position",position).putBoolean("panel_media",true).putBoolean("panel_media_idle",false).putBoolean("panel_brightness",true).putBoolean("panel_volume",true).putString("hand_side",left ? "left" : "right").commit();
        ArrayList<String> actions = new ArrayList<>(ControlGridChecks.ACTIONS); while (actions.size()<30) actions.add("app:fixture.controls/.Item"+actions.size()); prefs.saveActions("panel", actions);
        CoverService owner = new CoverService(); owner.screenContext=context; owner.prefs=prefs; owner.display=activity.getDisplay();
        try { var attach=android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class); attach.setAccessible(true); attach.invoke(owner,activity); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        DockGeometry.Box raw = new DockGeometry.Box(0,0,w,h), page = new DockGeometry.Box(12,18,w-24,h-36);
        DockGeometry.Box content = new DockGeometry.Box(narrow ? 90 : 18,80,w-(narrow ? 178 : 36),narrow ? 330 : page.bottom()-80);
        owner.placement=new DockGeometry.Placement(new DockGeometry.Box(0,0,1,1),new DockGeometry.Box(0,0,1,1),content,DockGeometry.BOTTOM,true);
        View body=owner.buildPanelContent("controls",raw,content.y()); InterfaceCard card=new InterfaceCard(context,prefs,InterfaceCard.CONTROLS,body,()->{},body instanceof ViewGroup group ? group::removeAllViews : ()->{});
        card.frameBounds(raw,page); card.contentSafeBounds(content); card.statusBounds(new DockGeometry.Box(page.x(),page.y(),page.width(),32));
        FrameLayout host=new FrameLayout(context); host.addView(card); activity.setContentView(host);
        try {
            measure(host,w,h);
            ViewGroup grid=card.findViewWithTag("control-tile-grid"), sliders=card.findViewWithTag("control-sliders"); View media=card.findViewWithTag("media-card"); ControlScrollView inner=card.findViewWithTag("control-grid-scroll");
            View dashboard=card.findViewWithTag("control-dashboard"); ScrollView outer=(ScrollView)dashboard.getParent().getParent();
            require(outer instanceof FadingScrollView && inner instanceof FadingScrollView,"both real scroll viewports use the common reading edge");
            DockGeometry.Box gridBox=box(inner,card), mediaBox=box(media,card), sliderBox=box(sliders,card);
            if (position.equals("bottom")) require(sliderBox.y()>=gridBox.bottom() && mediaBox.y()>=gridBox.bottom(),"explicit bottom choice is preserved");
            else require((left ? sliderBox.right()<=gridBox.x() && mediaBox.right()<=gridBox.x() : sliderBox.x()>=gridBox.right() && mediaBox.x()>=gridBox.right()) && mediaBox.y()>=sliderBox.bottom(),"auto/side never stacks after narrowing: "+columns+"/"+narrow+"/"+left);
            require(sliderBox.x()>=content.x() && mediaBox.x()>=content.x() && sliderBox.right()<=content.right() && mediaBox.right()<=content.right(),"tools stay inside horizontal safety bounds");
            checkMedia(owner,media.getWidth());
            require(sliders.getChildAt(0).getWidth()>0 && sliders.getChildAt(1).getWidth()>0,"both sliders retain usable geometry");
            require(grid.getChildAt(columns).getTop()>grid.getChildAt(0).getTop() && grid.getChildAt(columns-1).getTop()==grid.getChildAt(0).getTop(),"configured column count is unchanged");
            require(inner.getHeight()%4==0 && grid.getChildAt(columns).getTop()==inner.getHeight()/4,"four-row viewport is retained");
            if (narrow) require(outer.canScrollVertically(1),"short manual safe area uses outer scrolling instead of cutting off tools");
            // The production inner scroll receives a uniform marker, isolating the
            // viewport mask from icon artwork and background translucency.
            grid.setForeground(new ColorDrawable(Color.WHITE)); assertFade(inner,card,host,w,h);
            grid.setForeground(null); outer.getChildAt(0).setForeground(new ColorDrawable(Color.WHITE)); assertFade(outer,card,host,w,h); outer.getChildAt(0).setForeground(null);
            inner.setSmoothScrollingEnabled(false); outer.setSmoothScrollingEnabled(false); inner.fullScroll(View.FOCUS_DOWN); outer.fullScroll(View.FOCUS_DOWN); measure(host,w,h);
            require(!inner.canScrollVertically(1) && !outer.canScrollVertically(1),"both last positions are reachable");
            DockGeometry.Box last=box(grid.getChildAt(grid.getChildCount()-1),card); require(last.bottom()<=content.bottom(),"last grid row is above the content boundary");
            require(box(media,card).bottom()<=content.bottom(),"media bottom is fully reachable");
            require(prefs.panelColumns()==columns && prefs.panelToolsPosition().equals(position),"layout never rewrites user choices");
        } finally { card.release(); host.removeAllViews(); }
    }
    private void checkMedia(CoverService owner,int width) {
        MediaCardView media=new MediaCardView(owner,true,true); measure(media,width,media.minimumCardHeight(width)+Ui.dp(owner.screenContext,24));
        DockGeometry.Box artwork=box(media.findViewWithTag("media-artwork"),media), output=box(media.findViewWithTag("media-output"),media), transport=box(media.findViewWithTag("media-transport"),media);
        require(output.right()<=media.getWidth()-media.getPaddingRight() && artwork.right()<=output.x(),"compact full media keeps artwork and output inside its width");
        require(transport.bottom()<=media.getHeight()-media.getPaddingBottom(),"full media transport remains inside the card");
    }
    private void assertFade(ScrollView scroll, InterfaceCard card, View host, int w, int h) {
        scroll.scrollTo(0,0); measure(host,w,h); DockGeometry.Box visible=box(scroll,card).intersect(card.safeArea());
        // The inner grid may extend below a short outer viewport. Its own edge is
        // tested after the outer viewport reaches it; the outer edge is tested separately.
        if (visible.bottom()!=box(scroll,card).bottom()) return;
        int x=visible.x()+visible.width()/2, end=visible.bottom(); Bitmap image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        try {
            card.draw(new Canvas(image)); int red=Color.red(image.getPixel(x,end-2));
            require(scroll.canScrollVertically(1) ? red<60 : red>235,"viewport fades only while more content remains: "+red);
            scroll.setSmoothScrollingEnabled(false); scroll.fullScroll(View.FOCUS_DOWN); measure(host,w,h); image.eraseColor(Color.TRANSPARENT); card.draw(new Canvas(image));
            require(Color.red(image.getPixel(x,end-2))>235,"last content pixels are not permanently masked");
        } finally { image.recycle(); }
    }
    private static DockGeometry.Box box(View view, View host) { return SafeAreaManager.mapViewBounds(view,new DockGeometry.Box(0,0,view.getWidth(),view.getHeight()),host); }
    private static void measure(View view,int w,int h) { for(int i=0;i<3;i++){view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h);} }
    private void main(Runnable work) { Throwable[] error={null}; test.runOnMainSync(()->{try{work.run();}catch(Throwable failure){error[0]=failure;}}); if(error[0]!=null)throw new AssertionError(error[0]); }
    private void require(boolean value,String message) { assertions++; if(!value)throw new AssertionError(message); }
}
