package com.arkueid.alive2d;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public class Live2DModel {
    // Native handle that points to the native model instance
    private long nativeHandle;

    // Static method to create native instance and return handle
    private static native long nativeCreate();

    // Static method to destroy native instance
    private static native void nativeDestroy(long handle);

    // Load model from JSON
    private static native void nativeLoadModelJson(long handle, String modelJson);

    // Get model home directory
    private static native String nativeGetModelHomeDir(long handle);

    // Update model
    private static native void nativeUpdate(long handle, float deltaTimeSeconds);

    private static native boolean nativeUpdateMotion(long handle, float deltaTimeSeconds);

    private static native void nativeUpdateDrag(long handle, float deltaTimeSeconds);

    private static native boolean nativeUpdateReturnToDefault(long handle, float deltaTimeSeconds);

    private static native void nativeUpdateBreath(long handle, float deltaTimeSeconds);

    private static native void nativeUpdateBlink(long handle, float deltaTimeSeconds);

    private static native void nativeUpdateExpression(long handle, float deltaTimeSeconds);

    private static native void nativeUpdatePhysics(long handle, float deltaTimeSeconds);

    private static native void nativeUpdatePose(long handle, float deltaTimeSeconds);

    // Parameter operations
    private static native String[] nativeGetParameterIds(long handle);

    private static native float nativeGetParameterValue(long handle, int index);

    private static native float nativeGetParameterMaximumValue(long handle, int index);

    private static native float nativeGetParameterMinimumValue(long handle, int index);

    private static native float nativeGetParameterDefaultValue(long handle, int index);

    private static native void nativeSetParameterValue(long handle, int index, float value, float weight);

    private static native void nativeSetParameterValueById(long handle, String paramId, float value, float weight);

    private static native void nativeAddParameterValue(long handle, int index, float value);

    private static native void nativeAddParameterValueById(long handle, String paramId, float value);

    private static native void nativeSetAndSaveParameterValue(long handle, int index, float value, float weight);

    private static native void nativeSetAndSaveParameterValueById(long handle, String id, float value, float weight);

    private static native void nativeAddAndSaveParameterValue(long handle, int index, float value);

    private static native void nativeAddAndSaveParameterValueById(long handle, String id, float value);

    private static native void nativeLoadParameters(long handle);

    private static native void nativeSaveParameters(long handle);

    // Transform operations
    private static native void nativeResize(long handle, int width, int height);

    private static native void nativeSetOffset(long handle, float x, float y);

    private static native void nativeRotate(long handle, float degrees);

    private static native void nativeSetScale(long handle, float scale);

    private static native float[] nativeGetMvp(long handle);

    private static native float[] nativeScanMotionEnvelope(long handle, float dt, int maxSteps, float margin);

    // Motion control
    private static native void nativeStartMotion(long handle, String group, int no, int priority, BiConsumer<String, Integer> onStart, BiConsumer<String, Integer> onFinish);

    private static native void nativeStartRandomMotion(long handle, String group, int priority, BiConsumer<String, Integer> onStart, BiConsumer<String, Integer> onFinish);

    private static native boolean nativeIsMotionFinished(long handle);

    private static native void nativeLoadExtraMotion(long handle, String group, int no, String motionJsonPath);

    private static native Map<String, List<Map<String, String>>> nativeGetMotions(long handle);

    // Hit testing
    private static native List<String> nativeHitPart(long handle, float x, float y, boolean topOnly);

    private static native List<String> nativeHitDrawable(long handle, float x, float y, boolean topOnly);

    private static native void nativeDrag(long handle, float x, float y);

    private static native void nativeDragScene(long handle, float x, float y);

    private static native boolean nativeIsAreaHit(long handle, String areaName, float x, float y);

    private static native boolean nativeIsPartHit(long handle, int index, float x, float y);

    private static native boolean nativeIsDrawableHit(long handle, int index, float x, float y);

    private static native boolean nativeIsAnyDrawableHit(long handle, float x, float y);

    // Rendering
    private static native void nativeCreateRenderer(long handle, int maskBufferCount);

    private static native void nativeDestroyRenderer(long handle);

    private static native void nativeDraw(long handle);

    // Part operations
    private static native String[] nativeGetPartIds(long handle);

    private static native void nativeSetPartOpacity(long handle, int index, float opacity);

    private static native void nativeSetPartScreenColor(long handle, int index, float r, float g, float b, float a);

    private static native void nativeSetPartMultiplyColor(long handle, int index, float r, float g, float b, float a);

    // Drawable operations
    private static native String[] nativeGetDrawableIds(long handle);

    private static native void nativeSetDrawableMultiColor(long handle, int index, float r, float g, float b, float a);

    private static native void nativeSetDrawableScreenColor(long handle, int index, float r, float g, float b, float a);

    // Expression operations
    private static native void nativeAddExpression(long handle, String expressionId);

    private static native void nativeRemoveExpression(long handle, String expressionId);

    private static native void nativeSetExpression(long handle, String expressionId);

    private static native Map<String, String>[] nativeGetExpressions(long handle);

    private static native String nativeSetRandomExpression(long handle);

    private static native void nativeResetExpression(long handle);

    private static native void nativeFadeOutExpression(long handle);

    private static native void nativeResetExpressions(long handle);

    // Reset operations
    private static native void nativeStopAllMotions(long handle);

    private static native void nativeResetAllParameters(long handle);

    private static native void nativeResetPose(long handle);

    // Constructor
    public Live2DModel() {
        nativeHandle = nativeCreate();
    }

    // Destructor
    @Override
    protected void finalize() throws Throwable {
        try {
            if (nativeHandle != 0) {
                nativeDestroy(nativeHandle);
                nativeHandle = 0;
            }
        } finally {
            super.finalize();
        }
    }

    public void destroy() {
        if (nativeHandle != 0) {
            nativeDestroy(nativeHandle);
            nativeHandle = 0;
        }
    }

    // Public methods that wrap the native calls
    public void loadModelJson(String modelJson) {
        nativeLoadModelJson(nativeHandle, modelJson);
    }

    public String getModelHomeDir() {
        return nativeGetModelHomeDir(nativeHandle);
    }

    public void update(float deltaTimeSeconds) {
        nativeUpdate(nativeHandle, deltaTimeSeconds);
    }

    public boolean updateMotion(float deltaTimeSeconds) {
        return nativeUpdateMotion(nativeHandle, deltaTimeSeconds);
    }

    public void updateDrag(float deltaTimeSeconds) {
        nativeUpdateDrag(nativeHandle, deltaTimeSeconds);
    }

    /**
     * 把全部动作参数渐进回正到默认值（指数逼近，约 1.5s 收敛），用于动作播完后平滑回到站姿。
     * 不能用 resetAllParameters()：那是一帧硬复位，阶跃输入会激励物理摆锤振铃（乱晃）。
     *
     * @return true = 已全部回到默认值，可停止调用
     */
    public boolean updateReturnToDefault(float deltaTimeSeconds) {
        return nativeUpdateReturnToDefault(nativeHandle, deltaTimeSeconds);
    }

    public void updateBreath(float deltaTimeSeconds) {
        nativeUpdateBreath(nativeHandle, deltaTimeSeconds);
    }

    public void updateBlink(float deltaTimeSeconds) {
        nativeUpdateBlink(nativeHandle, deltaTimeSeconds);
    }

    public void updateExpression(float deltaTimeSeconds) {
        nativeUpdateExpression(nativeHandle, deltaTimeSeconds);
    }

    public void updatePhysics(float deltaTimeSeconds) {
        nativeUpdatePhysics(nativeHandle, deltaTimeSeconds);
    }

    public void updatePose(float deltaTimeSeconds) {
        nativeUpdatePose(nativeHandle, deltaTimeSeconds);
    }

    public String[] getParameterIds() {
        return nativeGetParameterIds(nativeHandle);
    }

    public float getParameterValue(int index) {
        return nativeGetParameterValue(nativeHandle, index);
    }

    public float getParameterMaximumValue(int index) {
        return nativeGetParameterMaximumValue(nativeHandle, index);
    }

    public float getParameterMinimumValue(int index) {
        return nativeGetParameterMinimumValue(nativeHandle, index);
    }

    public float getParameterDefaultValue(int index) {
        return nativeGetParameterDefaultValue(nativeHandle, index);
    }

    public void setParameterValue(int index, float value, float weight) {
        nativeSetParameterValue(nativeHandle, index, value, weight);
    }

    public void setParameterValue(int index, float value) {
        setParameterValue(index, value, 1.0f);
    }

    public void setParameterValue(String paramId, float value, float weight) {
        nativeSetParameterValueById(nativeHandle, paramId, value, weight);
    }

    public void setParameterValue(String paramId, float value) {
        setParameterValue(paramId, value, 1.0f);
    }

    public void addParameterValue(int index, float value) {
        nativeAddParameterValue(nativeHandle, index, value);
    }

    public void addParameterValue(String paramId, float value) {
        nativeAddParameterValueById(nativeHandle, paramId, value);
    }

    public void setAndSaveParameterValue(int index, float value, float weight) {
        nativeSetAndSaveParameterValue(nativeHandle, index, value, weight);
    }

    public void setAndSaveParameterValue(int index, float value) {
        setAndSaveParameterValue(index, value, 1.0f);
    }

    public void setAndSaveParameterValue(String id, float value, float weight) {
        nativeSetAndSaveParameterValueById(nativeHandle, id, value, weight);
    }

    public void setAndSaveParameterValue(String id, float value) {
        setAndSaveParameterValue(id, value, 1.0f);
    }

    public void addAndSaveParameterValue(int index, float value) {
        nativeAddAndSaveParameterValue(nativeHandle, index, value);
    }

    public void addAndSaveParameterValue(String id, float value) {
        nativeAddAndSaveParameterValueById(nativeHandle, id, value);
    }

    public void loadParameters() {
        nativeLoadParameters(nativeHandle);
    }

    public void saveParameters() {
        nativeSaveParameters(nativeHandle);
    }

    public void resize(int width, int height) {
        nativeResize(nativeHandle, width, height);
    }

    public void setOffset(float x, float y) {
        nativeSetOffset(nativeHandle, x, y);
    }

    public void rotate(float degrees) {
        nativeRotate(nativeHandle, degrees);
    }

    public void setScale(float scale) {
        nativeSetScale(nativeHandle, scale);
    }

    public float[] getMvp() {
        return nativeGetMvp(nativeHandle);
    }

    /**
     * 扫描全部动作 + 拖拽极值，求模型的最大活动包络（模型坐标系）。
     *
     * 纯 CPU：不需要 GL context / renderer，可在后台线程调用；但会重置动作与参数，
     * 所以用独立实例，或者在任何绘制开始之前调用。
     *
     * @param dt       采样步长（秒），0.1 左右够用
     * @param maxSteps 单个动作的采样步数上限，防止循环动作无限跑
     * @param margin   结果四周外扩比例（0.05 = 各边放大 5%）
     * @return 6 个 float：包络 minX/minY/maxX/maxY（模型坐标）+ 画布宽/高；失败返回 null
     */
    public float[] scanMotionEnvelope(float dt, int maxSteps, float margin) {
        return nativeScanMotionEnvelope(nativeHandle, dt, maxSteps, margin);
    }

    public void startMotion(String group, int no, int priority, BiConsumer<String, Integer> onStart, BiConsumer<String, Integer> onFinish) {
        nativeStartMotion(nativeHandle, group, no, priority, onStart, onFinish);
    }

    public void startMotion(String group, int no, int priority, BiConsumer<String, Integer> onStart) {
        nativeStartMotion(nativeHandle, group, no, priority, onStart, null);
    }

    public void startMotion(String group, int no, int priority) {
        nativeStartMotion(nativeHandle, group, no, priority, null, null);
    }

    public void startMotion(String group, int no) {
        nativeStartMotion(nativeHandle, group, no, MotionPriority.FORCE, null, null);
    }

    public void startRandomMotion(String group, int priority, BiConsumer<String, Integer> onStart, BiConsumer<String, Integer> onFinish) {
        nativeStartRandomMotion(nativeHandle, group, priority, onStart, onFinish);
    }

    public void startRandomMotion(String group, int priority, BiConsumer<String, Integer> onStart) {
        nativeStartRandomMotion(nativeHandle, group, priority, onStart, null);
    }

    public void startRandomMotion(String group, int priority) {
        nativeStartRandomMotion(nativeHandle, group, priority, null, null);
    }

    public void startRandomMotion(String group) {
        nativeStartRandomMotion(nativeHandle, group, MotionPriority.FORCE, null, null);
    }

    public void startRandomMotion() {
        nativeStartRandomMotion(nativeHandle, null, MotionPriority.FORCE, null, null);
    }

    public boolean isMotionFinished() {
        return nativeIsMotionFinished(nativeHandle);
    }

    public void loadExtraMotion(String group, int no, String motionJsonPath) {
        nativeLoadExtraMotion(nativeHandle, group, no, motionJsonPath);
    }

    public Map<String, List<Map<String, String>>> getMotions() {
        return nativeGetMotions(nativeHandle);
    }

    public List<String> hitPart(float x, float y, boolean topOnly) {
        return nativeHitPart(nativeHandle, x, y, topOnly);
    }

    public List<String> hitPart(float x, float y) {
        return hitPart(x, y, false);
    }

    public List<String> hitDrawable(float x, float y, boolean topOnly) {
        return nativeHitDrawable(nativeHandle, x, y, topOnly);
    }

    public List<String> hitDrawable(float x, float y) {
        return hitDrawable(x, y, false);
    }

    public void drag(float x, float y) {
        nativeDrag(nativeHandle, x, y);
    }

    /**
     * 设置注视目标（场景坐标语义：(0,0)=看正前，±1=满幅边缘）。
     * 注意与 {@link #drag(float, float)} 的区别：drag 是窗口像素语义（内部会 ScreenToScene），
     * drag(0,0) 是注视窗口左上角而不是回正——回正必须用本方法。
     */
    public void dragScene(float x, float y) {
        nativeDragScene(nativeHandle, x, y);
    }

    public boolean isAreaHit(String areaName, float x, float y) {
        return nativeIsAreaHit(nativeHandle, areaName, x, y);
    }

    public boolean isPartHit(int index, float x, float y) {
        return nativeIsPartHit(nativeHandle, index, x, y);
    }

    public boolean isDrawableHit(int index, float x, float y) {
        return nativeIsDrawableHit(nativeHandle, index, x, y);
    }

    /**
     * 点 (x,y)（窗口像素坐标）是否落在任一可见 drawable 的网格内（逐三角形，精确到轮廓边缘）。
     * 用于「仅角色外形可交互」的门控；opacity≈0 的 drawable 自动跳过。
     */
    public boolean isAnyDrawableHit(float x, float y) {
        return nativeIsAnyDrawableHit(nativeHandle, x, y);
    }

    public void createRenderer(int maskBufferCount) {
        nativeCreateRenderer(nativeHandle, maskBufferCount);
    }

    public void destroyRenderer() {
        nativeDestroyRenderer(nativeHandle);
    }

    public void draw() {
        nativeDraw(nativeHandle);
    }

    public String[] getPartIds() {
        return nativeGetPartIds(nativeHandle);
    }

    public void setPartOpacity(int index, float opacity) {
        nativeSetPartOpacity(nativeHandle, index, opacity);
    }

    public void setPartScreenColor(int index, float r, float g, float b) {
        nativeSetPartScreenColor(nativeHandle, index, r, g, b, 0.0f);
    }

    public void setPartMultiplyColor(int index, float r, float g, float b) {
        nativeSetPartMultiplyColor(nativeHandle, index, r, g, b, 1.0f);
    }

    public String[] getDrawableIds() {
        return nativeGetDrawableIds(nativeHandle);
    }

    public void setDrawableMultiColor(int index, float r, float g, float b) {
        nativeSetDrawableMultiColor(nativeHandle, index, r, g, b, 1.0f);
    }

    public void setDrawableScreenColor(int index, float r, float g, float b) {
        nativeSetDrawableScreenColor(nativeHandle, index, r, g, b, 0.0f);
    }

    public void addExpression(String expressionId) {
        nativeAddExpression(nativeHandle, expressionId);
    }

    public void removeExpression(String expressionId) {
        nativeRemoveExpression(nativeHandle, expressionId);
    }

    public void setExpression(String expressionId) {
        nativeSetExpression(nativeHandle, expressionId);
    }

    public Map<String, String>[] getExpressions() {
        return nativeGetExpressions(nativeHandle);
    }

    public String setRandomExpression() {
        return nativeSetRandomExpression(nativeHandle);
    }

    public void resetExpression() {
        nativeResetExpression(nativeHandle);
    }

    /**
     * 平滑淡出当前表情回默认脸：启动一个空表情走官方交叉淡化。
     * resetExpression() 是 StopAllMotions 硬停，表情权重 1 帧跳回 0，会激励物理摆锤振铃。
     */
    public void fadeOutExpression() {
        nativeFadeOutExpression(nativeHandle);
    }

    public void resetExpressions() {
        nativeResetExpressions(nativeHandle);
    }

    public void stopAllMotions() {
        nativeStopAllMotions(nativeHandle);
    }

    public void resetAllParameters() {
        nativeResetAllParameters(nativeHandle);
    }

    public void resetPose() {
        nativeResetPose(nativeHandle);
    }

    // Load the native library
    static {
        System.loadLibrary("alive2d");
    }
}