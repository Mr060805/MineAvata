/**
 * @brief Model.hpp
 * @author Arkueid
 * @date 2025/04/06
 * @note 更细粒度、更独立的 live2d 模型管理类
 */

#pragma once

#include <vector>
#include <string>
#include <unordered_map>

#include <Model/CubismUserModel.hpp>
#include <Motion/ACubismMotion.hpp>

#include <LAppTextureManager.hpp>
#include <MatrixManager.hpp>

using namespace Csm;

class Model : public Csm::CubismUserModel
{
public:
    Model();
    ~Model() override;

    /**
     * @brief
     * @param filePath model3.json path
     */
    void LoadModelJson(const char *filePath);

    const char* GetModelHomeDir();

    // update

    void Update(float deltaSecs);

    /**
     * @brief
     * @param deltaSecs time elapsed since last frame
     * @return true if motion is not finished and motion is updated
     */
    bool UpdateMotion(float deltaSecs);

    void UpdateDrag(float deltaSecs);

    /**
     * @brief 把全部动作参数渐进回正到默认值（指数逼近，约 1.5s 收敛）。
     *
     * 动作播完后参数冻结在收尾姿势；ResetAllParameters 的一帧硬复位是阶跃输入，
     * 会激励物理摆锤振铃（乱晃根源）。本方法逐帧小步逼近默认值，物理输入渐变不振铃，
     * 角色平滑回到默认站姿。在 LoadParameters 之后、SaveParameters 之前调用。
     *
     * @param deltaSecs 距上一帧的秒数
     * @return true = 已全部回到默认值，调用方可停止调用
     */
    bool UpdateReturnToDefault(float deltaSecs);

    void UpdateBreath(float deltaSecs);

    void UpdateBlink(float deltaSecs);

    void UpdateExpression(float deltaSecs);

    void UpdatePhysics(float deltaSecs);

    void UpdatePose(float deltaSecs);

    // param
    int GetParameterCount();

    void GetParameterIds(void* collector, void(*collect)(void* collector, const char* id));

    float GetParameterValue(int index);

    float GetParameterMaximumValue(int index);

    float GetParameterMinimumValue(int index);

    float GetParameterDefaultValue(int index);

    void SetParameterValue(const char *id, float value, float weight = 1.0f);

    void SetParameterValue(int index, float value, float weight = 1.0f);

    void AddParameterValue(const char *id, float value);

    void AddParameterValue(int index, float value);

    void SetAndSaveParameterValue(const char *id, float value, float weight = 1.0f);

    void SetAndSaveParameterValue(int index, float value, float weight = 1.0f);

    void AddAndSaveParameterValue(const char *id, float value);

    void AddAndSaveParameterValue(int index, float value);

    void LoadParameters();

    void SaveParameters();

    // transform
    void Resize(int width, int height);

    void SetOffset(float x, float y);

    void Rotate(float angle);

    void SetScale(float scale);

    const float* GetMvp();

    // motion
    void StartMotion(const char *group, int no, int priority = 3,
                     void *startCallee = nullptr, ACubismMotion::BeganMotionCallback startCalleeHandler = nullptr,
                     void *finishCallee = nullptr, ACubismMotion::FinishedMotionCallback finishCalleeHandler = nullptr);

    void StartRandomMotion(const char *group = nullptr, int priority = 3,
                           void *startCallee = nullptr, ACubismMotion::BeganMotionCallback startCalleeHandler = nullptr,
                           void *finishCallee = nullptr, ACubismMotion::FinishedMotionCallback finishCalleeHandler = nullptr);

    bool IsMotionFinished();

    void LoadExtraMotion(const char* group, int no, const char* motionJsonPath);

    int GetMotionGroupCount();

    int GetMotionCount(const char *group);

    void GetMotions(void* collector, void(*collect)(void* collector, const char* group, int no, const char* file, const char* sound));

    // mouse interaction
    void HitPart(float x, float y, void* collector, void(*collect)(void* collector, const char* id), bool topOnly = false);

    void HitDrawable(float x, float y, void* collector, void(*collect)(void* collector, const char* id), bool topOnly = false);

    void Drag(float x, float y);

    bool IsAreaHit(const char *areaName, float x, float y);

    bool IsPartHit(int index, float x, float y);

    bool IsDrawableHit(int index, float x, float y);

    /**
     * @brief 点 (窗口像素坐标) 是否落在任一可见 drawable 的网格内（逐三角形测试）。
     *
     * 比官方 IsHit 的包围盒精细到网格边缘；opacity≈0 的 drawable（闭眼、
     * 隐藏配饰）自动跳过。用于悬浮窗的「仅角色外形可交互」门控。
     */
    bool IsAnyDrawableHit(float x, float y);

    // render

    /**
     * @brief draw model
     */
    void CreateRenderer(int maskBufferCount = 1);

    void DestroyRenderer();

    void Draw();

    // part
    int GetPartCount();
    void GetPartIds(void* collector, void(*collect)(void* collector, const char* id));
    void SetPartOpacity(int index, float opacity);
    void SetPartScreenColor(int index, float r, float g, float b, float a);
    void SetPartMultiplyColor(int index, float r, float g, float b, float a);

    // drawable
    int GetDrawableCount();
    void GetDrawableIds(void* collector, void(*collect)(void* collector, const char* id));

    const float* GetDrawableVertices(int index);
    const int GetDrawableVertexCount(int index);
    const int GetDrawableVertexIndexCount(int index);
    const unsigned short* GetDrawableIndices(int index);

    void SetDrawableMultiColor(int index, float r, float g, float b, float a);
    void SetDrawableScreenColor(int index, float r, float g, float b, float a);

    // expression
    void AddExpression(const char *expressionId);

    void RemoveExpression(const char *expressionId);

    void SetExpression(const char *expressionId);

    const char* SetRandomExpression();

    void ResetExpressions();

    void ResetExpression();

    /**
     * @brief 用「空表情」把当前表情平滑交叉淡化回默认脸。
     *
     * ResetExpression() 的 StopAllMotions 是硬停：表情权重 1 帧跳回 0，
     * 阶跃会激励物理摆锤产生振铃。本方法走官方 StartMotion 交叉淡化路径。
     */
    void FadeOutExpression();

    int GetExpressionCount();

    void GetExpressions(void *collector, void(*collect)(void* collector, const char* id, const char* file));

    // reset
    void StopAllMotions();

    void ResetAllParameters();

    void ResetPose();

    // sizes
    void GetCanvasSize(float& w, float& h);

    void GetCanvasSizePixel(float& w, float& h);

    float GetPixelsPerUnit();

    /**
     * @brief 扫描全部动作 + 拖拽极值，求模型的最大活动包络（模型坐标系）。
     *
     * 纯 CPU：只驱动参数/物理并重算顶点，不碰 GL，也不需要 renderer。
     * 但扫描会重置动作队列与参数，勿对正在渲染的实例调用（用独立实例或渲染前调用）。
     *
     * @param dt       采样步长（秒），0.1 左右足够抓住动作包络
     * @param maxSteps 单个动作的采样步数上限，防止循环动作无限跑
     * @param margin   结果四周外扩比例（0.05 = 各边放大 5%），抵消没采样到的物理极值
     * @param out      输出 6 个 float：
     *                 [0..3] 包络 minX, minY, maxX, maxY（模型坐标）
     *                 [4..5] 画布宽, 高（模型坐标，调用方换算投影要用）
     * @return 模型未加载时返回 false，out 不写入
     */
    bool ScanMotionEnvelope(float dt, int maxSteps, float margin, float* out);

private:
    void ReleaseMotions();
    void ReleaseExpressions();
    void ReleaseExpressionManagers();
    void SetupTextures();
    void PreloadMotionGroup(const csmChar* group);
    void SetupModel();
    bool IsHit(CubismIdHandle drawableId, csmFloat32 pointX, csmFloat32 pointY) override;
private:
    ICubismModelSetting* _modelSetting;
    csmVector<CubismIdHandle> _eyeBlinkIds;
    csmVector<CubismIdHandle> _lipSyncIds;

    csmString _modelHomeDir;
    csmMap<Csm::csmString, ACubismMotion*> _motions;
    csmMap<Csm::csmString, ACubismMotion*> _expressions;
    std::unordered_map<std::string, CubismExpressionMotionManager*> _expManagers;
    ACubismMotion* _blankExpression = nullptr; ///< FadeOutExpression 缓存的空表情（不在 _expressions 里，析构单独释放）
    

    const Csm::CubismId* _idParamAngleX;
    const Csm::CubismId* _idParamAngleY;
    const Csm::CubismId* _idParamAngleZ;
    const Csm::CubismId* _idParamBodyAngleX;
    const Csm::CubismId* _idParamEyeBallX;
    const Csm::CubismId* _idParamEyeBallY;

    int _ParamAngleXi;
    int _ParamAngleYi;
    int _ParamAngleZi;
    int _ParamBodyAngleXi;
    int _ParamEyeBallXi;
    int _ParamEyeBallYi;

    LAppTextureManager _textureManager;

    MatrixManager _matrixManager;

    csmFloat32 _dragX;
    csmFloat32 _dragY;

    int* _tmpOrderedDrawIndice;
    const float* _parameterDefaultValues;
    float* _parameterValues;
    int _parameterCount;

    std::vector<csmString> _motionGroupNames;
    std::vector<int> _motionCounts;

    std::vector<float> _savedParameterValues;
};