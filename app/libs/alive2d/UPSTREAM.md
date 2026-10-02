# alive2d 第三方库说明

本目录来自开源项目 [EasyLive2D/alive2d](https://github.com/EasyLive2D/alive2d)(main 分支,commit `f948836`),以普通文件形式(vendored)并入本仓库,而非 git submodule。

## 为什么不作为 submodule

本项目在上游基础上做了本地修改(窗口包络 / fine-grained 模型参数支持),涉及:

- `alive2d/build.gradle.kts`
- `alive2d/src/main/cpp/Live2D/Main/src/MatrixManager.hpp`
- `alive2d/src/main/cpp/Live2D/Main/src/fine-grained/Model.cpp`
- `alive2d/src/main/cpp/Live2D/Main/src/fine-grained/Model.hpp`
- `alive2d/src/main/cpp/Live2D/cmake/Main.cmake`
- `alive2d/src/main/cpp/Live2DModel.cpp`
- `alive2d/src/main/java/com/arkueid/alive2d/Live2DModel.java`

修改后的代码只存在于本仓库,上游没有对应 commit,因此直接随主仓库提交,保证 clone 即可构建。

## 与上游同步

原始 `.git` 目录已从本目录移出、在开发机本地保留,后续如需对比或合并上游更新,可将其移回本目录恢复为独立仓库。
