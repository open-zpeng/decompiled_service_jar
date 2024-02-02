package com.xiaopeng.server.input.action;
/* loaded from: classes.dex */
public abstract class InputActionFunction implements ToActionFunction {
    private String mTag;

    public InputActionFunction(String tag) {
        this.mTag = tag;
    }

    public String getTag() {
        return this.mTag;
    }

    @Override // com.xiaopeng.server.input.action.ToActionFunction
    public boolean apply(Object var1) {
        return true;
    }
}
