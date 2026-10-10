package android.window;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;

import java.util.function.Supplier;

/**
 * 编译期占位（hidden-api stub）：framework 隐藏类 {@code android.window.DisplayWindowPolicyController}。
 *
 * 本模块仅以 compileOnly 参与 :app 编译，【不会】打进 APK；运行时子类的父类解析走
 * parent-first，命中 boot classpath 里 ROM 的真实类。抽象签名必须与目标 ROM
 * 逐一对齐 —— 已在 Android 16（LineageOS lmi / ColorOS PLQ110）反编译核对，均为以下 5 个：
 *
 * <ul>
 *   <li>{@code canActivityBeLaunched(ActivityInfo, Intent, int, int, boolean, boolean, Supplier<IntentSender>)}</li>
 *   <li>{@code canContainActivity(ActivityInfo, int, int, boolean)}（protected）</li>
 *   <li>{@code canShowTasksInHostDeviceRecents()}</li>
 *   <li>{@code getCustomHomeComponent()}</li>
 *   <li>{@code keepActivityOnWindowFlagsChanged(ActivityInfo, int, int)}</li>
 * </ul>
 *
 * 若某 ROM 的真实类多出新的抽象方法，子类实例化将抛 {@link InstantiationError}，
 * 调用侧必须 runCatching 并回退到「不注入」的现状行为。
 */
public abstract class DisplayWindowPolicyController {

    public abstract boolean canActivityBeLaunched(ActivityInfo activityInfo, Intent intent,
            int windowingMode, int activityType, boolean canShowAppErrorState,
            boolean pendingTransitionToHome, Supplier<IntentSender> transitionActivityOptions);

    protected abstract boolean canContainActivity(ActivityInfo activityInfo, int windowingMode,
            int activityType, boolean canBeEmbedded);

    public abstract boolean canShowTasksInHostDeviceRecents();

    public abstract ComponentName getCustomHomeComponent();

    public abstract boolean keepActivityOnWindowFlagsChanged(ActivityInfo activityInfo,
            int windowFlags, int systemWindowFlags);
}
