package com.android.server.am;

import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.UserManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class CarUserSwitchingDialog extends UserSwitchingDialog {
    private static final String TAG = "ActivityManagerCarUserSwitchingDialog";

    public CarUserSwitchingDialog(ActivityManagerService service, Context context, UserInfo oldUser, UserInfo newUser, boolean aboveSystem, String switchingFromSystemUserMessage, String switchingToSystemUserMessage) {
        super(service, context, oldUser, newUser, aboveSystem, switchingFromSystemUserMessage, switchingToSystemUserMessage);
        getWindow().setBackgroundDrawable(new ColorDrawable(0));
    }

    @Override // com.android.server.am.UserSwitchingDialog
    void inflateContent() {
        setCancelable(false);
        Resources res = getContext().getResources();
        View view = LayoutInflater.from(getContext()).inflate(17367108, (ViewGroup) null);
        UserManager userManager = (UserManager) getContext().getSystemService("user");
        Bitmap bitmap = userManager.getUserIcon(this.mNewUser.id);
        if (bitmap != null) {
            CircleFramedDrawable drawable = CircleFramedDrawable.getInstance(bitmap, res.getDimension(17104954));
            ((ImageView) view.findViewById(16909577)).setImageDrawable(drawable);
        }
        ((TextView) view.findViewById(16909576)).setText(res.getString(17039607));
        setView(view);
    }

    /* loaded from: classes.dex */
    static class CircleFramedDrawable extends Drawable {
        private final Bitmap mBitmap;
        private RectF mDstRect;
        private final Paint mPaint;
        private float mScale;
        private final int mSize;
        private Rect mSrcRect;

        public static CircleFramedDrawable getInstance(Bitmap icon, float iconSize) {
            CircleFramedDrawable instance = new CircleFramedDrawable(icon, (int) iconSize);
            return instance;
        }

        public CircleFramedDrawable(Bitmap icon, int size) {
            this.mSize = size;
            this.mBitmap = Bitmap.createBitmap(this.mSize, this.mSize, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(this.mBitmap);
            int width = icon.getWidth();
            int height = icon.getHeight();
            int square = Math.min(width, height);
            Rect cropRect = new Rect((width - square) / 2, (height - square) / 2, square, square);
            RectF circleRect = new RectF(0.0f, 0.0f, this.mSize, this.mSize);
            Path fillPath = new Path();
            fillPath.addArc(circleRect, 0.0f, 360.0f);
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            this.mPaint = new Paint();
            this.mPaint.setAntiAlias(true);
            this.mPaint.setColor(-16777216);
            this.mPaint.setStyle(Paint.Style.FILL);
            canvas.drawPath(fillPath, this.mPaint);
            this.mPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(icon, cropRect, circleRect, this.mPaint);
            this.mPaint.setXfermode(null);
            this.mScale = 1.0f;
            this.mSrcRect = new Rect(0, 0, this.mSize, this.mSize);
            this.mDstRect = new RectF(0.0f, 0.0f, this.mSize, this.mSize);
        }

        @Override // android.graphics.drawable.Drawable
        public void draw(Canvas canvas) {
            float inside = this.mScale * this.mSize;
            float pad = (this.mSize - inside) / 2.0f;
            this.mDstRect.set(pad, pad, this.mSize - pad, this.mSize - pad);
            canvas.drawBitmap(this.mBitmap, this.mSrcRect, this.mDstRect, (Paint) null);
        }

        @Override // android.graphics.drawable.Drawable
        public int getOpacity() {
            return -3;
        }

        @Override // android.graphics.drawable.Drawable
        public void setAlpha(int alpha) {
        }

        @Override // android.graphics.drawable.Drawable
        public void setColorFilter(ColorFilter colorFilter) {
        }
    }
}
