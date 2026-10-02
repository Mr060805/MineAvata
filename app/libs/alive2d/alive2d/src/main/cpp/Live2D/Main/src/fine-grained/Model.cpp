#include "Model.hpp"

#include <Id/CubismIdManager.hpp>
#include <CubismDefaultParameterId.hpp>
#include <Live2DCubismCore.hpp>
#include <CubismModelSettingJson.hpp>
#include <Rendering/OpenGL/CubismShader_OpenGLES2.hpp>
#include <Utils/CubismString.hpp>
#include <Motion/CubismMotion.hpp>
#include <Motion/CubismExpressionMotion.hpp>

#include <Log.hpp>
#include <LAppPal.hpp>
#include <LAppDefine.hpp>

#include <filesystem>
#include <functional>
#include <unordered_set>
#include <algorithm>
#include <cfloat>
#include <cmath>

using namespace Live2D::Cubism::Framework;
using namespace LAppDefine;
using namespace Live2D::Cubism::Framework::DefaultParameterId;
using namespace Live2D::Cubism::Core;

namespace
{
    class FakeMotion : public ACubismMotion
    {
    protected:
        void DoUpdateParameters(CubismModel *model, csmFloat32 userTimeSeconds, csmFloat32 weight,
                                CubismMotionQueueEntry *motionQueueEntry) override
        {
        }

    public:
        FakeMotion() = default;
    };

    void LoadAssets(const std::string &filePath, const std::function<void(csmByte *, csmSizeInt)> &afterLoadCallback)
    {
        csmSizeInt bufferSize = 0;
        csmByte *buffer = nullptr;

        if (filePath.empty())
        {
            return;
        }

        buffer = LAppPal::LoadFileAsBytes(filePath.c_str(), &bufferSize);

        afterLoadCallback(buffer, bufferSize);

        LAppPal::ReleaseBytes(buffer);
    }
}

Model::Model() : CubismUserModel(), _modelSetting(nullptr), _matrixManager(),
                 _parameterCount(0), _parameterDefaultValues(nullptr), _parameterValues(nullptr),
                 _tmpOrderedDrawIndice(nullptr)
{
    _mocConsistency = true;

    _idParamAngleX = CubismFramework::GetIdManager()->GetId(ParamAngleX);
    _idParamAngleY = CubismFramework::GetIdManager()->GetId(ParamAngleY);
    _idParamAngleZ = CubismFramework::GetIdManager()->GetId(ParamAngleZ);
    _idParamBodyAngleX = CubismFramework::GetIdManager()->GetId(ParamBodyAngleX);
    _idParamEyeBallX = CubismFramework::GetIdManager()->GetId(ParamEyeBallX);
    _idParamEyeBallY = CubismFramework::GetIdManager()->GetId(ParamEyeBallY);
}

Model::~Model()
{
    _textureManager.ReleaseTextures();

    ReleaseMotions();
    ReleaseExpressions();
    ReleaseExpressionManagers();

    if (_blankExpression != nullptr)
    {
        ACubismMotion::Delete(_blankExpression);
        _blankExpression = nullptr;
    }

    if (_modelSetting == nullptr)
    {
        return;
    }

    delete _modelSetting;
}

void Model::LoadModelJson(const char *filePath)
{
    std::filesystem::path p = std::filesystem::u8path(filePath);
    _modelHomeDir = p.parent_path().generic_u8string().c_str();
    _modelHomeDir += "/";

    Info("load modelSetting: %s", filePath);
    LoadAssets(filePath, [&](csmByte *buffer, csmSizeInt size)
               { _modelSetting = new CubismModelSettingJson(buffer, size); });

    SetupModel();
}

const char *Model::GetModelHomeDir()
{
    return _modelHomeDir.GetRawString();
}

void Model::Update(float deltaSecs)
{
    _dragManager->Update(deltaSecs);
    _dragX = _dragManager->GetX();
    _dragY = _dragManager->GetY();

    bool motionUpdated = false;
    _model->LoadParameters();
    if (!_motionManager->IsFinished())
    {
        motionUpdated = _motionManager->UpdateMotion(_model, deltaSecs);
    }
    _model->SaveParameters();

    _opacity = _model->GetModelOpacity();

    if (!motionUpdated)
    {
        if (_eyeBlink != NULL)
        {
            _eyeBlink->UpdateParameters(_model, deltaSecs);
        }
    }

    if (_expressionManager != NULL)
    {
        _expressionManager->UpdateMotion(_model, deltaSecs);
    }

    _model->AddParameterValue(_ParamAngleXi, _dragX * 30);
    _model->AddParameterValue(_ParamAngleYi, _dragY * 30);
    _model->AddParameterValue(_ParamAngleZi, _dragX * _dragY * -30);

    _model->AddParameterValue(_ParamBodyAngleXi, _dragX * 10);

    _model->AddParameterValue(_ParamEyeBallXi, _dragX);
    _model->AddParameterValue(_ParamEyeBallYi, _dragY);

    if (_breath != NULL)
    {
        _breath->UpdateParameters(_model, deltaSecs);
    }

    if (_physics != NULL)
    {
        _physics->Evaluate(_model, deltaSecs);
    }

    if (_pose != NULL)
    {
        _pose->UpdateParameters(_model, deltaSecs);
    }
}

void Model::SetupModel()
{
    // moc3
    if (strcmp(_modelSetting->GetModelFileName(), "") != 0)
    {
        csmString path = _modelSetting->GetModelFileName();
        path = _modelHomeDir + path;

        Info("create model: %s", _modelSetting->GetModelFileName());

        LoadAssets(path.GetRawString(),
                   [&](csmByte *buffer, csmSizeInt size)
                   {
                       LoadModel(buffer, size, _mocConsistency);
                   });
    }

    if (_model == nullptr)
    {
        Error("Failed to SetupModel()");
    }

    // exp3.json
    if (_modelSetting->GetExpressionCount() > 0)
    {
        const csmInt32 count = _modelSetting->GetExpressionCount();
        for (csmInt32 i = 0; i < count; i++)
        {
            csmString name = _modelSetting->GetExpressionName(i);
            csmString path = _modelHomeDir + _modelSetting->GetExpressionFileName(i);

            LoadAssets(path.GetRawString(),
                       [&](csmByte *buffer, csmSizeInt size)
                       {
                           ACubismMotion *motion = LoadExpression(buffer, size, name.GetRawString());
                           if (motion)
                           {
                               std::string key = name.GetRawString();
                               if (_expressions[name] != nullptr)
                               {
                                   ACubismMotion::Delete(_expressions[name]);
                                   _expressions[name] = nullptr;
                               }
                               if (_expManagers[key] != nullptr)
                               {
                                   CSM_DELETE(_expManagers[key]);
                                   _expManagers.erase(key);
                               }
                               _expressions[name] = motion;
                               _expManagers[key] = CSM_NEW CubismExpressionMotionManager();
                           }
                       });
        }
    }

    // physics3.json
    if (strcmp(_modelSetting->GetPhysicsFileName(), "") != 0)
    {
        csmString path = _modelHomeDir + _modelSetting->GetPhysicsFileName();

        LoadAssets(path.GetRawString(),
                   [&](csmByte *buffer, csmSizeInt size)
                   {
                       LoadPhysics(buffer, size);
                   });
    }

    // pose3.json
    if (strcmp(_modelSetting->GetPoseFileName(), "") != 0)
    {
        csmString path = _modelHomeDir + _modelSetting->GetPoseFileName();

        LoadAssets(path.GetRawString(),
                   [&](csmByte *buffer, csmSizeInt size)
                   {
                       LoadPose(buffer, size);
                   });
    }

    // EyeBlink
    if (_modelSetting->GetEyeBlinkParameterCount() > 0)
    {
        _eyeBlink = CubismEyeBlink::Create(_modelSetting);
    }

    // Breath
    {
        _breath = CubismBreath::Create();

        csmVector<CubismBreath::BreathParameterData> breathParameters;

        breathParameters.PushBack(CubismBreath::BreathParameterData(_idParamAngleX, 0.0f, 15.0f, 6.5345f, 0.5f));
        breathParameters.PushBack(CubismBreath::BreathParameterData(_idParamAngleY, 0.0f, 8.0f, 3.5345f, 0.5f));
        breathParameters.PushBack(CubismBreath::BreathParameterData(_idParamAngleZ, 0.0f, 10.0f, 5.5345f, 0.5f));
        breathParameters.PushBack(CubismBreath::BreathParameterData(_idParamBodyAngleX, 0.0f, 4.0f, 15.5345f, 0.5f));
        breathParameters.PushBack(
            CubismBreath::BreathParameterData(CubismFramework::GetIdManager()->GetId(ParamBreath), 0.5f, 0.5f, 3.2345f,
                                              0.5f));

        _breath->SetParameters(breathParameters);
    }

    // UserData
    if (strcmp(_modelSetting->GetUserDataFile(), "") != 0)
    {
        csmString path = _modelHomeDir + _modelSetting->GetUserDataFile();
        LoadAssets(path.GetRawString(),
                   [&](csmByte *buffer, csmSizeInt size)
                   {
                       LoadUserData(buffer, size);
                   });
    }

    // EyeBlinkIds
    {
        csmInt32 eyeBlinkIdCount = _modelSetting->GetEyeBlinkParameterCount();
        for (csmInt32 i = 0; i < eyeBlinkIdCount; ++i)
        {
            _eyeBlinkIds.PushBack(_modelSetting->GetEyeBlinkParameterId(i));
        }
    }

    // LipSyncIds
    {
        csmInt32 lipSyncIdCount = _modelSetting->GetLipSyncParameterCount();
        for (csmInt32 i = 0; i < lipSyncIdCount; ++i)
        {
            _lipSyncIds.PushBack(_modelSetting->GetLipSyncParameterId(i));
        }
    }

    if (_modelSetting == nullptr || _modelMatrix == nullptr)
    {
        Error("Failed to SetupModel()");
        return;
    }

    // Layout
    csmMap<csmString, csmFloat32> layout;
    _modelSetting->GetLayoutMap(layout);
    _modelMatrix->SetupFromLayout(layout);

    // motion3.json
    _motionGroupNames.clear();
    _motionCounts.clear();
    for (csmInt32 i = 0; i < _modelSetting->GetMotionGroupCount(); i++)
    {
        const csmChar *group = _modelSetting->GetMotionGroupName(i);
        PreloadMotionGroup(group);
    }
    _motionManager->StopAllMotions();

    _matrixManager.SetModelWH(_model->GetCanvasWidth(), _model->GetCanvasHeight());

    _ParamAngleXi = _model->GetParameterIndex(_idParamAngleX);
    _ParamAngleYi = _model->GetParameterIndex(_idParamAngleY);
    _ParamAngleZi = _model->GetParameterIndex(_idParamAngleZ);
    _ParamBodyAngleXi = _model->GetParameterIndex(_idParamBodyAngleX);
    _ParamEyeBallXi = _model->GetParameterIndex(_idParamEyeBallX);
    _ParamEyeBallYi = _model->GetParameterIndex(_idParamEyeBallY);

    _tmpOrderedDrawIndice = new int[_model->GetDrawableCount()];
    csmModel *model = _model->GetModel();
    _parameterDefaultValues = csmGetParameterDefaultValues(model);
    _parameterValues = csmGetParameterValues(model);
    _parameterCount = csmGetParameterCount(model);

    _savedParameterValues.resize(_parameterCount);
    SaveParameters();
}

bool Model::IsHit(CubismIdHandle drawableId, csmFloat32 pointX, csmFloat32 pointY)
{
    const csmInt32 drawIndex = _model->GetDrawableIndex(drawableId);

    if (drawIndex < 0)
    {
        return false; // 存在しない場合はfalse
    }

    const csmInt32 count = _model->GetDrawableVertexCount(drawIndex);
    const csmFloat32 *vertices = _model->GetDrawableVertices(drawIndex);

    csmFloat32 left = vertices[0];
    csmFloat32 right = vertices[0];
    csmFloat32 top = vertices[1];
    csmFloat32 bottom = vertices[1];

    for (csmInt32 j = 1; j < count; ++j)
    {
        csmFloat32 x = vertices[Constant::VertexOffset + j * Constant::VertexStep];
        csmFloat32 y = vertices[Constant::VertexOffset + j * Constant::VertexStep + 1];

        if (x < left)
        {
            left = x; // Min x
        }

        if (x > right)
        {
            right = x; // Max x
        }

        if (y < top)
        {
            top = y; // Min y
        }

        if (y > bottom)
        {
            bottom = y; // Max y
        }
    }

    return ((left <= pointX) && (pointX <= right) && (top <= pointY) && (pointY <= bottom));
}

bool Model::UpdateMotion(float deltaSecs)
{
    _opacity = _model->GetModelOpacity();
    return !_motionManager->IsFinished() && _motionManager->UpdateMotion(_model, deltaSecs);
}

void Model::UpdateDrag(float deltaSecs)
{
    _dragManager->Update(deltaSecs);
    _dragX = _dragManager->GetX();
    _dragY = _dragManager->GetY();

    _model->AddParameterValue(_ParamAngleXi, _dragX * 30);
    _model->AddParameterValue(_ParamAngleYi, _dragY * 30);
    _model->AddParameterValue(_ParamAngleZi, _dragX * _dragY * -30);

    _model->AddParameterValue(_ParamBodyAngleXi, _dragX * 10);

    _model->AddParameterValue(_ParamEyeBallXi, _dragX);
    _model->AddParameterValue(_ParamEyeBallYi, _dragY);
}

bool Model::UpdateReturnToDefault(float deltaSecs)
{
    if (_model == nullptr)
    {
        return true;
    }

    // 指数逼近：时间常数 0.35s，约 1.5s 收敛到默认值附近。
    // 动作播完后参数会冻结在收尾姿势（多数模型动作首尾不闭合），必须回正；
    // 但 ResetAllParameters 那种一帧硬复位是阶跃输入，会激励物理摆锤振铃数秒。
    // 这里让物理收到的输入是渐变的：参数每帧只挪一小步，摆锤自然跟随、不振铃。
    const float alpha = std::fmin(1.0f, deltaSecs / 0.35f);

    const csmInt32 count = _model->GetParameterCount();
    bool done = true;
    for (csmInt32 i = 0; i < count; i++)
    {
        const float def = _model->GetParameterDefaultValue(i);
        const float cur = _model->GetParameterValue(i);
        const float diff = def - cur;
        // 容差随参数量程缩放：量程的 0.2%（AngleX 量程 60 → 0.12°，肉眼不可见）
        const float eps = (_model->GetParameterMaximumValue(i) - _model->GetParameterMinimumValue(i)) * 0.002f;
        if (std::fabs(diff) <= eps)
        {
            if (cur != def)
            {
                _model->SetParameterValue(i, def, 1.0f);
            }
        }
        else
        {
            _model->SetParameterValue(i, cur + diff * alpha, 1.0f);
            done = false;
        }
    }
    return done;
}

void Model::UpdateBreath(float deltaSecs)
{
    if (_breath == nullptr)
    {
        return;
    }
    _breath->UpdateParameters(_model, deltaSecs);
}

void Model::UpdateBlink(float deltaSecs)
{
    if (_eyeBlink == nullptr)
    {
        return;
    }
    _eyeBlink->UpdateParameters(_model, deltaSecs);
}

void Model::UpdateExpression(float deltaSecs)
{
    if (_expressionManager->IsFinished())
    {
        for (auto &pair : _expManagers)
        {
            pair.second->UpdateMotion(_model, deltaSecs);
        }
    }
    else
    {
        _expressionManager->UpdateMotion(_model, deltaSecs);
    }
}

void Model::UpdatePhysics(float deltaSecs)
{
    if (_physics == nullptr)
    {
        return;
    }

    _physics->Evaluate(_model, deltaSecs);
}

void Model::UpdatePose(float deltaSecs)
{
    if (_pose == nullptr)
    {
        return;
    }

    _pose->UpdateParameters(_model, deltaSecs);
}

int Model::GetParameterCount()
{
    return _model->GetParameterCount();
}

void Model::GetParameterIds(void *collector, void (*collect)(void *collector, const char *id))
{
    for (csmInt32 i = 0; i < _parameterCount; ++i)
    {
        collect(collector, _model->GetParameterId(i)->GetString().GetRawString());
    }
}

float Model::GetParameterValue(int index)
{
    return _model->GetParameterValue(index);
}

float Model::GetParameterMaximumValue(int index)
{
    return _model->GetParameterMaximumValue(index);
}

float Model::GetParameterMinimumValue(int index)
{
    return _model->GetParameterMinimumValue(index);
}

float Model::GetParameterDefaultValue(int index)
{
    return _model->GetParameterDefaultValue(index);
}

void Model::SetParameterValue(const char *id, float value, float weight)
{
    const CubismId *handle = CubismFramework::GetIdManager()->GetId(id);
    _model->SetParameterValue(handle, value, weight);
}

void Model::SetParameterValue(int index, float value, float weight)
{
    _model->SetParameterValue(index, value, weight);
}

void Model::AddParameterValue(const char *id, float value)
{
    const CubismId *handle = CubismFramework::GetIdManager()->GetId(id);
    _model->AddParameterValue(handle, value);
}

void Model::AddParameterValue(int index, float value)
{
    _model->AddParameterValue(index, value);
}

void Model::SetAndSaveParameterValue(const char *id, float value, float weight)
{
    const CubismId* handle = CubismFramework::GetIdManager()->GetId(id);
    const int index = _model->GetParameterIndex(handle);
    _model->SetParameterValue(index, value, weight);
    if (index < _parameterCount)
    {
        _savedParameterValues[index] = _parameterValues[index];
    }
}

void Model::SetAndSaveParameterValue(int index, float value, float weight)
{
    _model->SetParameterValue(index, value, weight);
    if (index < _parameterCount)
    {
        _savedParameterValues[index] = _parameterValues[index];
    }
}

void Model::AddAndSaveParameterValue(const char *id, float value)
{
    const CubismId* handle = CubismFramework::GetIdManager()->GetId(id);
    const int index = _model->GetParameterIndex(handle);
    _model->AddParameterValue(index, value);
    if (index < _parameterCount)
    {
        _savedParameterValues[index] = _parameterValues[index];
    }
}

void Model::AddAndSaveParameterValue(int index, float value)
{
    _model->AddParameterValue(index, value);
    if (index < _parameterCount)
    {
        _savedParameterValues[index] = _parameterValues[index];
    }
}

void Model::LoadParameters()
{
    for (int i = 0; i < _parameterCount; ++i)
    {
        _model->SetParameterValue(i, _savedParameterValues[i]);
    }
}

void Model::SaveParameters()
{
    for (int i = 0; i < _parameterCount; ++i)
    {
        _savedParameterValues[i] = _parameterValues[i];
    }
}

void Model::Resize(int width, int height)
{
    _matrixManager.UpdateScreenToScene(width, height);
}

void Model::SetOffset(float x, float y)
{
    _matrixManager.SetOffset(x, y);
}

void Model::Rotate(float angle)
{
    _matrixManager.Rotate(angle);
}

void Model::SetScale(float scale)
{
    _matrixManager.SetScale(scale);
}

const float *Model::GetMvp()
{
    return _matrixManager.GetMvp().GetArray();
}

void Model::StartMotion(const char *group, int no, int priority, void *startCallee, ACubismMotion::BeganMotionCallback onStartMotionHandler, void *finishCallee, ACubismMotion::FinishedMotionCallback onFinishMotionHandler)
{
    if (priority == PriorityForce)
    {
        _motionManager->SetReservePriority(priority);
    }
    else if (!_motionManager->ReserveMotion(priority))
    {
        Info("motion priority is too low.");
        return;
    }

    // ex) idle_0
    csmString name = Utils::CubismString::GetFormatedString("%s_%d", group, no);
    CubismMotion *motion = static_cast<CubismMotion *>(_motions[name.GetRawString()]);
    csmBool autoDelete = false;

    csmBool hasMotion = true;

    if (motion == NULL)
    {
        // 加载临时 motion
        const csmString fileName = _modelSetting->GetMotionFileName(group, no);
        if (fileName.GetLength() <= 0)
        {
            hasMotion = false;
            Info("motion(%s) has no file attached", name.GetRawString());
            goto handler_label;
        }

        csmString path = fileName;

        path = _modelHomeDir + path;

        LoadAssets(path.GetRawString(),
                   [&](csmByte *buffer, csmSizeInt size)
                   {
                       motion = static_cast<CubismMotion *>(LoadMotion(buffer, size, NULL));

                       if (motion)
                       {
                           csmFloat32 fadeTime = _modelSetting->GetMotionFadeInTimeValue(group, no);
                           if (fadeTime >= 0.0f)
                           {
                               motion->SetFadeInTime(fadeTime);
                           }

                           fadeTime = _modelSetting->GetMotionFadeOutTimeValue(group, no);
                           if (fadeTime >= 0.0f)
                           {
                               motion->SetFadeOutTime(fadeTime);
                           }
                           motion->SetEffectIds(_eyeBlinkIds, _lipSyncIds);
                           autoDelete = true; // 終了時にメモリから削除
                       }
                   });
        Info("load tmp motion(%s)", name.GetRawString());
    }

    if (motion)
    {
        // fade 兕底：model3.json 未声明时（GetMotionFadeInTimeValue 返回 -1）给 0.5s，
        // 避免切换动作时旧动作被瞬时结束（SDK 默认 -1 = 无淡入淡出 = 硬切闪变）。
        // 无论动作来自缓存还是临时加载，都走这里，两条路径全覆盖。
        if (motion->GetFadeInTime() < 0.0f)
        {
            motion->SetFadeInTime(0.5f);
        }
        if (motion->GetFadeOutTime() < 0.0f)
        {
            motion->SetFadeOutTime(0.5f);
        }
        motion->group = group;
        motion->no = no;
        motion->SetBeganMotionCustomData(startCallee);
        motion->SetFinishedMotionCustomData(finishCallee);
        motion->SetBeganMotionHandler(onStartMotionHandler);
        motion->SetFinishedMotionHandler(onFinishMotionHandler);
    }

handler_label:

    if (!hasMotion)
    {
        // 添加空指针判断，如果 motion 文件不存在，直接调用动作结束回调函数
        // 修复模型文件不存在时，导致崩溃
        FakeMotion fakeMotion;
        fakeMotion.group = group;
        fakeMotion.no = no;
        fakeMotion.SetBeganMotionCustomData(startCallee);
        fakeMotion.SetFinishedMotionCustomData(finishCallee);
        if (onStartMotionHandler)
        {
            onStartMotionHandler(&fakeMotion);
        }
        if (onFinishMotionHandler)
        {
            onFinishMotionHandler(&fakeMotion);
        }
        _motionManager->SetReservePriority(PriorityNone);
    }

    _motionManager->StartMotionPriority(motion, autoDelete, priority);
}

void Model::StartRandomMotion(const char *group, int priority, void *startCallee, ACubismMotion::BeganMotionCallback startCalleeHandler, void *finishCallee, ACubismMotion::FinishedMotionCallback finishCalleeHandler)
{
    csmString g;
    int gindex = -1;
    if (group == nullptr)
    {
        int gcnt = _motionGroupNames.size();
        if (gcnt > 0)
        {
            gindex = rand() % gcnt;
            g = _motionGroupNames[gindex];
        }
    }
    else
    {
        g = group;
        for (csmInt32 i = 0; i < _motionGroupNames.size(); i++)
        {
            if (_motionGroupNames[i] == g)
            {
                gindex = i;
                break;
            }
        }
    }

    if (gindex < 0)
    {
        Info("no motion start", g.GetRawString());
        return;
    }

    csmInt32 no = rand() % _motionCounts[gindex];

    StartMotion(g.GetRawString(), no, priority, startCallee, startCalleeHandler, finishCallee,
                finishCalleeHandler);
}

bool Model::IsMotionFinished()
{
    return _motionManager->IsFinished();
}

void Model::LoadExtraMotion(const char *group, int no, const char *motionJsonPath)
{
    csmString name = Utils::CubismString::GetFormatedString("%s_%d", group, no);

    LoadAssets(motionJsonPath,
               [&](csmByte *buffer, csmSizeInt size)
               {
                   CubismMotion *tmpMotion = static_cast<CubismMotion *>(LoadMotion(buffer, size, name.GetRawString(), NULL, NULL, _modelSetting, group, no));
                   if (tmpMotion)
                   {
                       tmpMotion->SetEffectIds(_eyeBlinkIds, _lipSyncIds);

                       bool replace = _motions[name] != NULL;
                       if (replace)
                       {
                           ACubismMotion::Delete(_motions[name]);
                       }
                       _motions[name] = tmpMotion;
                       Info("Load extra motion: %s => [%s]", motionJsonPath, name.GetRawString());

                       if (replace)
                       {
                           return;
                       }

                       int i = 0;
                       bool found = false;
                       for (auto &s : _motionGroupNames)
                       {
                           if (s == group)
                           {
                               _motionCounts[i] = _motionCounts[i] + 1;
                               found = true;
                               break;
                           }
                           i++;
                       }
                       if (!found)
                       {
                           _motionGroupNames.push_back(group);
                           _motionCounts.push_back(1);
                       }
                   }
               });
}

int Model::GetMotionGroupCount()
{
    return _modelSetting->GetMotionGroupCount();
}

int Model::GetMotionCount(const char *group)
{
    return _modelSetting->GetMotionCount(group);
}

void Model::GetMotions(void *collector, void (*collect)(void *collector, const char *group, int no, const char *file, const char *sound))
{
    const int count = _modelSetting->GetMotionGroupCount();
    for (int i = 0; i < count; i++)
    {
        const char *group = _modelSetting->GetMotionGroupName(i);
        const int motionCount = _modelSetting->GetMotionCount(group);
        for (int j = 0; j < motionCount; j++)
        {
            const char *file = _modelSetting->GetMotionFileName(group, j);
            const char *sound = _modelSetting->GetMotionSoundFileName(group, j);
            collect(collector, group, j, file, sound);
        }
    }
}

static bool isInTriangle(const csmVector2 p0, const csmVector2 p1, const csmVector2 p2, const csmVector2 p)
{
    // https://github.com/Arkueid/live2d-py/issues/18
    // 情况1：
    //  要检测的三角形很多，说明模型很精细，那么一定程度上三角形的面积会很小，
    //  只有少量的三角形的范围检测会失败，增加的额外计算量不会太大，
    //  因此只需要简单判断范围即可回避大量浮点计算
    // 情况2：
    //  要检测的三角形比较少，说明模型很粗糙，那么总的计算量就会相对较少，
    //  增加几次范围检测理论上是可以接受的
    // 总结为：需要计算叉积的实际三角形其实不会很多，因此范围检测可以避免大部分计算

    // 范围检测
    if (p.X < std::min({p0.X, p1.X, p2.X}))
    {
        return false;
    }
    if (p.X > std::max({p0.X, p1.X, p2.X}))
    {
        return false;
    }
    if (p.Y < std::min({p0.Y, p1.Y, p2.Y}))
    {
        return false;
    }
    if (p.Y > std::max({p0.Y, p1.Y, p2.Y}))
    {
        return false;
    }

    // 叉积检测
    const float dX = p.X - p2.X;
    const float dY = p.Y - p2.Y;
    const float dX21 = p2.X - p1.X;
    const float dY12 = p1.Y - p2.Y;
    const float D = dY12 * (p0.X - p2.X) + dX21 * (p0.Y - p2.Y);
    const float s = dY12 * dX + dX21 * dY;
    const float t = (p2.Y - p0.Y) * dX + (p0.X - p2.X) * dY;
    if (D < 0)
        return s <= 0 && t <= 0 && s + t >= D;
    return s >= 0 && t >= 0 && s + t <= D;
}

void Model::HitPart(float x, float y, void *collector, void (*collect)(void *collector, const char *id), bool topOnly)
{
    _matrixManager.ScreenToScene(&x, &y);
    _matrixManager.InvertTransform(&x, &y);
    const csmInt32 drawableCount = _model->GetDrawableCount();
    const csmInt32 *renderOrders = _model->GetDrawableRenderOrders();
    for (csmInt32 i = 0; i < drawableCount; i++)
    {
        // 绘制顺序，先绘制的被后绘制的覆盖
        _tmpOrderedDrawIndice[drawableCount - 1 - renderOrders[i]] = i;
    }
    // 多个 part index 可能指向同一个 part id，所以用 part id set
    std::unordered_set<const char *> hitParts;
    bool topClicked = false;

    for (int i = 0; i < drawableCount; i++)
    {
        int drawableIndex = _tmpOrderedDrawIndice[i];
        if (_model->GetDrawableOpacity(drawableIndex) == 0.0f)
        {
            continue;
        }
        int partIndex = _model->GetDrawableParentPartIndex(drawableIndex);
        if (partIndex == -1)
        {
            // 绘制对象不属于 part
            continue;
        }
        const char *partId = _model->GetPartId(partIndex)->GetString().GetRawString();
        if (_model->GetPartOpacity(partIndex) == 0.0f)
        {
            continue;
        }
        // 已经点击过的部件
        if (hitParts.find(partId) != hitParts.end())
        {
            continue;
        }
        // 顶点连线个数，3个顶点一个三角形，一定是3的整数倍
        const int indexCount = _model->GetDrawableVertexIndexCount(drawableIndex);
        // 顶点坐标
        const csmVector2 *vertices = _model->GetDrawableVertexPositions(drawableIndex);
        // 三角形顶点索引
        const csmUint16 *indices = _model->GetDrawableVertexIndices(drawableIndex);
        const int triangleCount = indexCount / 3;

        for (int j = 0; j < triangleCount; j++)
        {
            if (!isInTriangle(vertices[indices[j * 3]], vertices[indices[j * 3 + 1]], vertices[indices[j * 3 + 2]],
                              {x, y}))
            {
                continue;
            }
            collect(collector, partId);
            hitParts.insert(partId);
            topClicked = true;
            break;
        }

        if (topOnly && topClicked)
        {
            break;
        }
    }
}

void Model::HitDrawable(float x, float y, void *collector, void (*collect)(void *collector, const char *id), bool topOnly)
{
    _matrixManager.ScreenToScene(&x, &y);
    _matrixManager.InvertTransform(&x, &y);

    const csmInt32 drawableCount = _model->GetDrawableCount();
    const csmInt32 *renderOrders = _model->GetDrawableRenderOrders();
    for (csmInt32 i = 0; i < drawableCount; i++)
    {
        // 绘制顺序，先绘制的被后绘制的覆盖
        _tmpOrderedDrawIndice[drawableCount - 1 - renderOrders[i]] = i;
    }
    bool topClicked = false;

    for (int i = 0; i < drawableCount; i++)
    {
        int drawableIndex = _tmpOrderedDrawIndice[i];
        if (_model->GetDrawableOpacity(drawableIndex) == 0.0f)
        {
            continue;
        }
        const char *drawableId = _model->GetDrawableId(drawableIndex)->GetString().GetRawString();

        // 顶点连线个数，3个顶点一个三角形，一定是3的整数倍
        const int indexCount = _model->GetDrawableVertexIndexCount(drawableIndex);
        // 顶点坐标
        const csmVector2 *vertices = _model->GetDrawableVertexPositions(drawableIndex);
        // 三角形顶点索引
        const csmUint16 *indices = _model->GetDrawableVertexIndices(drawableIndex);
        const int triangleCount = indexCount / 3;

        for (int j = 0; j < triangleCount; j++)
        {
            if (!isInTriangle(vertices[indices[j * 3]], vertices[indices[j * 3 + 1]], vertices[indices[j * 3 + 2]],
                              {x, y}))
            {
                continue;
            }
            collect(collector, drawableId);
            topClicked = true;
            break;
        }

        if (topOnly && topClicked)
        {
            break;
        }
    }
}

bool Model::IsAreaHit(const char *areaName, float x, float y)
{
    _matrixManager.ScreenToScene(&x, &y);
    _matrixManager.InvertTransform(&x, &y);

    if (_opacity < 1)
    {
        return false;
    }
    const csmInt32 count = _modelSetting->GetHitAreasCount();
    for (csmInt32 i = 0; i < count; i++)
    {
        if (strcmp(_modelSetting->GetHitAreaName(i), areaName) == 0)
        {
            const CubismIdHandle drawID = _modelSetting->GetHitAreaId(i);
            return IsHit(drawID, x, y);
        }
    }
    return false;
}

bool Model::IsPartHit(int index, float x, float y)
{
    _matrixManager.ScreenToScene(&x, &y);
    _matrixManager.InvertTransform(&x, &y);

    if (_model->GetPartOpacity(index) == 0.0f)
    {
        return false;
    }

    const csmInt32 drawableCount = _model->GetDrawableCount();
    const csmInt32 *renderOrders = _model->GetDrawableRenderOrders();
    for (csmInt32 i = 0; i < drawableCount; i++)
    {
        // 绘制顺序，先绘制的被后绘制的覆盖
        _tmpOrderedDrawIndice[drawableCount - 1 - renderOrders[i]] = i;
    }

    for (int i = 0; i < drawableCount; i++)
    {
        int drawableIndex = _tmpOrderedDrawIndice[i];
        if (_model->GetDrawableOpacity(drawableIndex) == 0.0f)
        {
            continue;
        }
        int partIndex = _model->GetDrawableParentPartIndex(drawableIndex);
        if (partIndex != index) // 不是该 part 的 drawable
        {
            continue;
        }
        const char *partId = _model->GetPartId(partIndex)->GetString().GetRawString();

        // 顶点连线个数，3个顶点一个三角形，一定是3的整数倍
        const int indexCount = _model->GetDrawableVertexIndexCount(drawableIndex);
        // 顶点坐标
        const csmVector2 *vertices = _model->GetDrawableVertexPositions(drawableIndex);
        // 三角形顶点索引
        const csmUint16 *indices = _model->GetDrawableVertexIndices(drawableIndex);
        const int triangleCount = indexCount / 3;

        for (int j = 0; j < triangleCount; j++)
        {
            if (!isInTriangle(vertices[indices[j * 3]], vertices[indices[j * 3 + 1]], vertices[indices[j * 3 + 2]],
                              {x, y}))
            {
                continue;
            }
            return true;
        }
    }
    return false;
}

bool Model::IsDrawableHit(int index, float x, float y)
{
    _matrixManager.ScreenToScene(&x, &y);   // 屏幕到OpenGL坐标系
    _matrixManager.InvertTransform(&x, &y); // OpenGL坐标系到模型坐标系

    // 顶点连线个数，3个顶点一个三角形，一定是3的整数倍
    const int indexCount = _model->GetDrawableVertexIndexCount(index);
    // 顶点坐标
    const csmVector2 *vertices = _model->GetDrawableVertexPositions(index);
    // 三角形顶点索引
    const csmUint16 *indices = _model->GetDrawableVertexIndices(index);
    const int triangleCount = indexCount / 3;

    for (int j = 0; j < triangleCount; j++)
    {
        if (!isInTriangle(vertices[indices[j * 3]], vertices[indices[j * 3 + 1]], vertices[indices[j * 3 + 2]],
                          {x, y}))
        {
            continue;
        }
        return true;
        break;
    }
    return false;
}

bool Model::IsAnyDrawableHit(float x, float y)
{
    if (_model == nullptr)
    {
        return false;
    }
    _matrixManager.ScreenToScene(&x, &y);   // 屏幕到OpenGL坐标系
    _matrixManager.InvertTransform(&x, &y); // OpenGL坐标系到模型坐标系

    const csmInt32 drawableCount = _model->GetDrawableCount();
    for (csmInt32 i = 0; i < drawableCount; i++)
    {
        // 不可见的 drawable（闭眼、隐藏配饰、opacity 淡出中）不参与命中
        if (_model->GetDrawableOpacity(i) <= 0.01f)
        {
            continue;
        }

        const int indexCount = _model->GetDrawableVertexIndexCount(i);
        const csmVector2 *vertices = _model->GetDrawableVertexPositions(i);
        const csmUint16 *indices = _model->GetDrawableVertexIndices(i);
        if (vertices == nullptr || indices == nullptr)
        {
            continue;
        }
        const int triangleCount = indexCount / 3;

        for (int j = 0; j < triangleCount; j++)
        {
            if (isInTriangle(vertices[indices[j * 3]], vertices[indices[j * 3 + 1]],
                             vertices[indices[j * 3 + 2]], {x, y}))
            {
                return true;
            }
        }
    }
    return false;
}

void Model::Drag(float x, float y)
{
    _matrixManager.ScreenToScene(&x, &y);
    // 横向归一化：ScreenToScene 的 x 范围是 ±(窗宽/窗高)，而 CubismTargetPoint
    // 的目标语义是 ±1（中心 0、边缘满幅）。竖长窗口下不归一的话，左右注视/转头
    // 幅度会按宽高比缩水（宽高比 0.5 时只剩一半）；这里把 x 拉回 ±1 语义。
    const int ww = _matrixManager.GetScreenWidth();
    const int wh = _matrixManager.GetScreenHeight();
    if (ww > 0 && wh > 0)
    {
        x *= static_cast<float>(wh) / static_cast<float>(ww);
    }
    SetDragging(x, y);
}

void Model::CreateRenderer(int maskBufferCount)
{
    _textureManager.ReleaseTextures();
    CubismUserModel::CreateRenderer(maskBufferCount);
    SetupTextures();
}

void Model::DestroyRenderer()
{
    _textureManager.ReleaseTextures();
    CubismUserModel::DeleteRenderer();
}

void Model::Draw()
{
    if (_model == nullptr)
    {
        return;
    }

    _model->Update();

    CubismMatrix44 &matrix = _matrixManager.GetMvp();
    Rendering::CubismRenderer_OpenGLES2 *renderer = GetRenderer<Rendering::CubismRenderer_OpenGLES2>();

    renderer->SetMvpMatrix(&matrix);

    renderer->DrawModel();
}

int Model::GetPartCount()
{
    return _model->GetPartCount();
}

void Model::GetPartIds(void *collector, void (*collect)(void *collector, const char *id))
{
    for (csmInt32 i = 0; i < _model->GetPartCount(); i++)
    {
        collect(collector, _model->GetPartId(i)->GetString().GetRawString());
    }
}

void Model::SetPartOpacity(int index, float opacity)
{
    _model->SetPartOpacity(index, opacity);
}

void Model::SetPartScreenColor(int index, float r, float g, float b, float a)
{
    _model->SetPartScreenColor(index, r, g, b, a);
    if (_model->GetOverwriteColorForPartScreenColors(index))
    {
        return;
    }
    _model->SetOverwriteColorForPartScreenColors(index, true);
}

void Model::SetPartMultiplyColor(int index, float r, float g, float b, float a)
{
    _model->SetPartMultiplyColor(index, r, g, b, a);
    if (_model->GetOverwriteColorForPartMultiplyColors(index))
    {
        return;
    }
    _model->SetOverwriteColorForPartMultiplyColors(index, true);
}

int Model::GetDrawableCount()
{
    return _model->GetDrawableCount();
}

void Model::GetDrawableIds(void *collector, void (*collect)(void *collector, const char *id))
{
    const int count = _model->GetDrawableCount();
    for (int i = 0; i < count; i++)
    {
        collect(collector, _model->GetDrawableId(i)->GetString().GetRawString());
    }
}

const float *Model::GetDrawableVertices(int index)
{
    return _model->GetDrawableVertices(index);
}

const int Model::GetDrawableVertexCount(int index)
{
    return _model->GetDrawableVertexCount(index);
}

const int Model::GetDrawableVertexIndexCount(int index)
{
    return _model->GetDrawableVertexIndexCount(index);
}

const unsigned short *Model::GetDrawableIndices(int index)
{
    return _model->GetDrawableVertexIndices(index);
}

void Model::SetDrawableMultiColor(int index, float r, float g, float b, float a)
{
    const int count = _model->GetDrawableVertexCount(index);
    if (index < 0 || index >= count)
    {
        return;
    }
    _model->SetOverwriteFlagForDrawableMultiplyColors(index, true);
    _model->SetMultiplyColor(index, r, g, b, a);
}

void Model::SetDrawableScreenColor(int index, float r, float g, float b, float a)
{
    const int count = _model->GetDrawableVertexCount(index);
    if (index < 0 || index >= count)
    {
        return;
    }
    _model->SetOverwriteFlagForDrawableScreenColors(index, true);
    _model->SetScreenColor(index, r, g, b, a);
}

void Model::AddExpression(const char *expressionId)
{
    ACubismMotion *motion = _expressions[expressionId];

    Info("expression: [%s]", expressionId);

    if (motion != nullptr)
    {
        _expManagers[expressionId]->StartMotion(motion, false);
    }
    else
    {
        Info("expression[%s] is null ", expressionId);
    }
}

void Model::RemoveExpression(const char *expressionId)
{
    if (_expManagers.find(expressionId) == _expManagers.end())
    {
        return;
    }
    _expManagers[expressionId]->StopAllMotions();

    Info("reset expression: [%s]", expressionId);
}

void Model::SetExpression(const char *expressionId)
{
    ACubismMotion *motion = _expressions[expressionId];

    Info("expression: [%s]", expressionId);

    if (motion != nullptr)
    {
        _expressionManager->StartMotion(motion, false);
    }
    else
    {
        Info("expression[%s] is null ", expressionId);
    }
}

const char *Model::SetRandomExpression()
{
    const int size = _expressions.GetSize();
    if (size == 0)
    {
        return nullptr;
    }
    csmInt32 no = rand() % size;
    csmMap<csmString, ACubismMotion *>::const_iterator map_ite;
    csmInt32 i = 0;
    for (map_ite = _expressions.Begin(); map_ite != _expressions.End(); map_ite++)
    {
        if (i == no)
        {
            csmString name = (*map_ite).First;
            SetExpression(name.GetRawString());
            return name.GetRawString();
        }
        i++;
    }
    return nullptr;
}

void Model::ResetExpressions()
{
    for (auto &[id, expMgr] : _expManagers)
    {
        expMgr->StopAllMotions();
    }
    _expressionManager->StopAllMotions();

    Info("reset expressions");
}

void Model::ResetExpression()
{
    _expressionManager->StopAllMotions();
}

void Model::FadeOutExpression()
{
    if (_expressionManager == nullptr)
    {
        return;
    }
    if (_blankExpression == nullptr)
    {
        // 空表情：不改任何参数，作为交叉淡化的目标 = 默认脸。
        // Cubism 表情 entry 播完不删除（保持型），只能靠启动新表情把旧的淡化掉。
        static const char BlankExpJson[] =
            "{\"Type\":\"Live2D Expression\",\"FadeInTime\":0.5,\"FadeOutTime\":0.5,\"Parameters\":[]}";
        _blankExpression = CubismExpressionMotion::Create(
            reinterpret_cast<const csmByte *>(BlankExpJson), sizeof(BlankExpJson) - 1);
    }
    if (_blankExpression != nullptr)
    {
        // 与 SetExpression 相同的官方入队方式；缓存实例复用，不 autoDelete
        _expressionManager->StartMotion(_blankExpression, false);
    }
}

int Model::GetExpressionCount()
{
    return _modelSetting->GetExpressionCount();
}

void Model::GetExpressions(void *collector, void (*collect)(void *collector, const char *id, const char *file))
{
    const int count = _modelSetting->GetExpressionCount();
    for (int i = 0; i < count; i++)
    {
        const char *file = _modelSetting->GetExpressionFileName(i);
        const char *id = _modelSetting->GetExpressionName(i);
        collect(collector, id, file);
    }
}

void Model::StopAllMotions()
{
    _motionManager->StopAllMotions();
}

void Model::ResetAllParameters()
{
    for (int i = 0; i < _parameterCount; i++)
    {
        _parameterValues[i] = _parameterDefaultValues[i];
        _savedParameterValues[i] = _parameterDefaultValues[i];
    }
}

void Model::ResetPose()
{
    if (_pose != nullptr)
    {
        _pose->Reset(_model);
    }
}

void Model::GetCanvasSize(float &w, float &h)
{
    w = _model->GetCanvasWidth();
    h = _model->GetCanvasHeight();
}

void Model::GetCanvasSizePixel(float &w, float &h)
{
    w = _model->GetCanvasWidthPixel();
    h = _model->GetCanvasHeightPixel();
}

float Model::GetPixelsPerUnit()
{
    return _model->GetPixelsPerUnit();
}

bool Model::ScanMotionEnvelope(float dt, int maxSteps, float margin, float *out)
{
    if (_model == nullptr)
    {
        return false;
    }
    if (dt <= 0.0f)
    {
        dt = 0.1f;
    }
    if (maxSteps < 1)
    {
        maxSteps = 60;
    }

    const int drawableCount = _model->GetDrawableCount();
    if (drawableCount <= 0)
    {
        return false;
    }

    // 顶点位置（csmGetDrawableVertexPositions）本身就在模型坐标系，
    // 与 _scale / offset / 视口无关 —— 投影换算交给调用方，这里只求"模型占多大"。
    float minX = FLT_MAX, minY = FLT_MAX, maxX = -FLT_MAX, maxY = -FLT_MAX;
    bool any = false;

    auto accumulate = [&]()
    {
        _model->Update(); // csmUpdateModel：重算顶点与不透明度
        for (int i = 0; i < drawableCount; i++)
        {
            // 完全不透明的部件才计入：跳过作者标记为 0 不透明度的 drawable
            if (_model->GetDrawableOpacity(i) <= 0.001f)
            {
                continue;
            }
            const float *vertices = _model->GetDrawableVertices(i);
            const int vertexCount = _model->GetDrawableVertexCount(i);
            if (vertices == nullptr || vertexCount <= 0)
            {
                continue;
            }
            for (int k = 0; k < vertexCount; k++)
            {
                const float x = vertices[k * 2];
                const float y = vertices[k * 2 + 1];
                minX = std::min(minX, x);
                maxX = std::max(maxX, x);
                minY = std::min(minY, y);
                maxY = std::max(maxY, y);
                any = true;
            }
        }
    };

    // 推进一帧。参数更新顺序与 Model::Update 保持一致（动作→眨眼→表情→拖拽→呼吸→物理→姿态）
    auto step = [&](float dragX, float dragY, bool withMotion)
    {
        _model->LoadParameters();
        bool motionUpdated = false;
        if (withMotion && !_motionManager->IsFinished())
        {
            motionUpdated = _motionManager->UpdateMotion(_model, dt);
        }
        _model->SaveParameters();

        if (!motionUpdated && _eyeBlink != nullptr)
        {
            _eyeBlink->UpdateParameters(_model, dt);
        }
        if (_expressionManager != nullptr)
        {
            _expressionManager->UpdateMotion(_model, dt);
        }

        // 拖拽极值直接注入，绕开 _dragManager 的平滑跟随
        _model->AddParameterValue(_ParamAngleXi, dragX * 30);
        _model->AddParameterValue(_ParamAngleYi, dragY * 30);
        _model->AddParameterValue(_ParamAngleZi, dragX * dragY * -30);
        _model->AddParameterValue(_ParamBodyAngleXi, dragX * 10);
        _model->AddParameterValue(_ParamEyeBallXi, dragX);
        _model->AddParameterValue(_ParamEyeBallYi, dragY);

        if (_breath != nullptr)
        {
            _breath->UpdateParameters(_model, dt);
        }
        if (_physics != nullptr)
        {
            _physics->Evaluate(_model, dt);
        }
        if (_pose != nullptr)
        {
            _pose->UpdateParameters(_model, dt);
        }

        accumulate();
    };

    // 1) 逐个动作空跑一遍
    for (size_t g = 0; g < _motionGroupNames.size(); g++)
    {
        const int count = _motionCounts[g];
        for (int no = 0; no < count; no++)
        {
            StopAllMotions();
            ResetAllParameters();
            StartMotion(_motionGroupNames[g].GetRawString(), no, PriorityForce);
            for (int s = 0; s < maxSteps && !IsMotionFinished(); s++)
            {
                step(0.0f, 0.0f, true);
            }
        }
    }

    // 2) 拖拽四个极值。物理是状态量，每个方向多跑一会儿让摆动到位再取包络
    const float dragExtremes[4][2] = {{-1.0f, -1.0f}, {1.0f, -1.0f}, {-1.0f, 1.0f}, {1.0f, 1.0f}};
    for (int e = 0; e < 4; e++)
    {
        StopAllMotions();
        ResetAllParameters();
        for (int s = 0; s < maxSteps; s++)
        {
            step(dragExtremes[e][0], dragExtremes[e][1], false);
        }
    }

    // 3) 静止姿态
    StopAllMotions();
    ResetAllParameters();
    for (int s = 0; s < 2; s++)
    {
        step(0.0f, 0.0f, false);
    }

    // 复位：别把模型留在扫描中间态
    StopAllMotions();
    ResetAllParameters();

    if (!any)
    {
        return false;
    }

    // 四周外扩 margin，抵消动作交接处物理摆动的溢出
    const float padX = (maxX - minX) * margin * 0.5f;
    const float padY = (maxY - minY) * margin * 0.5f;
    out[0] = minX - padX;
    out[1] = minY - padY;
    out[2] = maxX + padX;
    out[3] = maxY + padY;
    GetCanvasSize(out[4], out[5]);
    Info("motion envelope: [%.3f, %.3f, %.3f, %.3f] canvas [%.3f, %.3f]",
         out[0], out[1], out[2], out[3], out[4], out[5]);
    return true;
}

void Model::ReleaseMotions()
{
    for (csmMap<csmString, ACubismMotion *>::const_iterator iter = _motions.Begin(); iter != _motions.End(); ++iter)
    {
        ACubismMotion::Delete(iter->Second);
    }

    _motions.Clear();
}

void Model::ReleaseExpressions()
{
    for (csmMap<csmString, ACubismMotion *>::const_iterator iter = _expressions.Begin(); iter != _expressions.End(); ++iter)
    {
        ACubismMotion::Delete(iter->Second);
    }

    _expressions.Clear();
}

void Model::ReleaseExpressionManagers()
{
    for (auto &[id, expMgr] : _expManagers)
    {
        delete expMgr;
    }
    _expManagers.clear();
}

void Model::SetupTextures()
{
    for (csmInt32 modelTextureNumber = 0; modelTextureNumber < _modelSetting->GetTextureCount(); modelTextureNumber++)
    {
        if (strcmp(_modelSetting->GetTextureFileName(modelTextureNumber), "") == 0)
        {
            continue;
        }

        csmString texturePath = _modelSetting->GetTextureFileName(modelTextureNumber);
        texturePath = _modelHomeDir + texturePath;

        // 已经加载过的纹理会直接复用
        LAppTextureManager::TextureInfo *texture = _textureManager.CreateTextureFromPngFile(texturePath.GetRawString());
        const csmInt32 glTextueNumber = texture->id;

        // OpenGL
        GetRenderer<Rendering::CubismRenderer_OpenGLES2>()->BindTexture(modelTextureNumber, glTextueNumber);
    }

#ifdef PREMULTIPLIED_ALPHA_ENABLE
    GetRenderer<Rendering::CubismRenderer_OpenGLES2>()->IsPremultipliedAlpha(true);
#else
    GetRenderer<Rendering::CubismRenderer_OpenGLES2>()->IsPremultipliedAlpha(false);
#endif
}

void Model::PreloadMotionGroup(const csmChar *group)
{
    const csmInt32 count = _modelSetting->GetMotionCount(group);

    if (count > 0)
    {
        _motionGroupNames.push_back(group);
        _motionCounts.push_back(count);
    }

    for (csmInt32 i = 0; i < count; i++)
    {
        // ex) idle_0
        csmString name = Utils::CubismString::GetFormatedString("%s_%d", group, i);
        csmString path = _modelSetting->GetMotionFileName(group, i);
        path = _modelHomeDir + path;

        Info("load motion: %s => [%s_%d] ", path.GetRawString(), group, i);

        LoadAssets(path.GetRawString(),
                   [&](csmByte *buffer, csmInt32 size)
                   {
                       CubismMotion *tmpMotion = static_cast<CubismMotion *>(LoadMotion(buffer, size, name.GetRawString(), NULL, NULL, _modelSetting, group, i));
                       if (tmpMotion)
                       {
                           tmpMotion->SetEffectIds(_eyeBlinkIds, _lipSyncIds);

                           if (_motions[name] != NULL)
                           {
                               ACubismMotion::Delete(_motions[name]);
                           }
                           _motions[name] = tmpMotion;
                       }
                   });
    }
}
