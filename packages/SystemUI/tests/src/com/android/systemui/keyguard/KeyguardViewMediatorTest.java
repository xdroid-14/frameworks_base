/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.keyguard;

import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY;
import static android.view.WindowManagerPolicyConstants.OFF_BECAUSE_OF_USER;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;
import static com.android.systemui.keyguard.KeyguardViewMediator.REBOOT_MAINLINE_UPDATE;
import static com.android.systemui.keyguard.KeyguardViewMediator.SYS_BOOT_REASON_PROP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.telephony.TelephonyManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.RemoteAnimationTarget;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.InstanceId;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardDisplayManager;
import com.android.keyguard.KeyguardSecurityView;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.mediator.ScreenOnCoordinator;
import com.android.systemui.DejankUtils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.classifier.FalsingCollectorFake;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.flags.SystemPropertiesHelper;
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.NotificationShadeWindowControllerImpl;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.shade.ShadeWindowLogger;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.DeviceConfigProxyFake;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.wm.shell.keyguard.KeyguardTransitions;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.flow.Flow;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class KeyguardViewMediatorTest extends SysuiTestCase {
    private KeyguardViewMediator mViewMediator;

    private @Mock UserTracker mUserTracker;
    private @Mock DevicePolicyManager mDevicePolicyManager;
    private @Mock LockPatternUtils mLockPatternUtils;
    private @Mock KeyguardUpdateMonitor mUpdateMonitor;
    private @Mock StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private @Mock BroadcastDispatcher mBroadcastDispatcher;
    private @Mock DismissCallbackRegistry mDismissCallbackRegistry;
    private @Mock DumpManager mDumpManager;
    private @Mock WindowManager mWindowManager;
    private @Mock IActivityManager mActivityManager;
    private @Mock ConfigurationController mConfigurationController;
    private @Mock PowerManager mPowerManager;
    private @Mock TrustManager mTrustManager;
    private @Mock UserSwitcherController mUserSwitcherController;
    private @Mock NavigationModeController mNavigationModeController;
    private @Mock KeyguardDisplayManager mKeyguardDisplayManager;
    private @Mock KeyguardBypassController mKeyguardBypassController;
    private @Mock DozeParameters mDozeParameters;
    private @Mock SysuiStatusBarStateController mStatusBarStateController;
    private @Mock KeyguardStateController mKeyguardStateController;
    private @Mock NotificationShadeDepthController mNotificationShadeDepthController;
    private @Mock KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    private @Mock ScreenOffAnimationController mScreenOffAnimationController;
    private @Mock InteractionJankMonitor mInteractionJankMonitor;
    private @Mock ScreenOnCoordinator mScreenOnCoordinator;
    private @Mock KeyguardTransitions mKeyguardTransitions;
    private @Mock ShadeController mShadeController;
    private NotificationShadeWindowController mNotificationShadeWindowController;
    private @Mock DreamOverlayStateController mDreamOverlayStateController;
    private @Mock ActivityLaunchAnimator mActivityLaunchAnimator;
    private @Mock ScrimController mScrimController;
    private @Mock SysuiColorExtractor mColorExtractor;
    private @Mock AuthController mAuthController;
    private @Mock ShadeExpansionStateManager mShadeExpansionStateManager;
    private @Mock ShadeWindowLogger mShadeWindowLogger;
    private @Captor ArgumentCaptor<KeyguardUpdateMonitorCallback>
            mKeyguardUpdateMonitorCallbackCaptor;
    private @Captor ArgumentCaptor<KeyguardStateController.Callback>
            mKeyguardStateControllerCallback;
    private DeviceConfigProxy mDeviceConfig = new DeviceConfigProxyFake();
    private FakeExecutor mUiBgExecutor = new FakeExecutor(new FakeSystemClock());

    private FalsingCollectorFake mFalsingCollector;

    private @Mock CentralSurfaces mCentralSurfaces;
    private @Mock UiEventLogger mUiEventLogger;
    private @Mock SessionTracker mSessionTracker;
    private @Mock SystemPropertiesHelper mSystemPropertiesHelper;
    private @Mock CoroutineDispatcher mDispatcher;
    private @Mock DreamingToLockscreenTransitionViewModel mDreamingToLockscreenTransitionViewModel;

    private FakeFeatureFlags mFeatureFlags;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mFalsingCollector = new FalsingCollectorFake();

        when(mLockPatternUtils.getDevicePolicyManager()).thenReturn(mDevicePolicyManager);
        when(mPowerManager.newWakeLock(anyInt(), any())).thenReturn(mock(WakeLock.class));
        when(mInteractionJankMonitor.begin(any(), anyInt())).thenReturn(true);
        when(mInteractionJankMonitor.end(anyInt())).thenReturn(true);
        final ViewRootImpl testViewRoot = mock(ViewRootImpl.class);
        when(testViewRoot.getView()).thenReturn(mock(View.class));
        when(mStatusBarKeyguardViewManager.getViewRootImpl()).thenReturn(testViewRoot);
        when(mDreamingToLockscreenTransitionViewModel.getDreamOverlayAlpha())
                .thenReturn(mock(Flow.class));
        when(mDreamingToLockscreenTransitionViewModel.getTransitionEnded())
                .thenReturn(mock(Flow.class));
        mNotificationShadeWindowController = new NotificationShadeWindowControllerImpl(mContext,
                mWindowManager, mActivityManager, mDozeParameters, mStatusBarStateController,
                mConfigurationController, mViewMediator, mKeyguardBypassController,
                mColorExtractor, mDumpManager, mKeyguardStateController,
                mScreenOffAnimationController, mAuthController, mShadeExpansionStateManager,
                mShadeWindowLogger);
        mFeatureFlags = new FakeFeatureFlags();


        DejankUtils.setImmediate(true);

        createAndStartViewMediator();
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void onLockdown_showKeyguard_evenIfKeyguardIsNotEnabledExternally() {
        // GIVEN keyguard is not enabled and isn't showing
        mViewMediator.onSystemReady();
        mViewMediator.setKeyguardEnabled(false);
        TestableLooper.get(this).processAllMessages();
        captureKeyguardUpdateMonitorCallback();
        assertFalse(mViewMediator.isShowingAndNotOccluded());

        // WHEN lockdown occurs
        when(mLockPatternUtils.isUserInLockdown(anyInt())).thenReturn(true);
        mKeyguardUpdateMonitorCallbackCaptor.getValue().onStrongAuthStateChanged(0);

        // THEN keyguard is shown
        TestableLooper.get(this).processAllMessages();
        assertTrue(mViewMediator.isShowingAndNotOccluded());
    }

    @Test
    public void testOnGoingToSleep_UpdatesKeyguardGoingAway() {
        mViewMediator.onStartedGoingToSleep(OFF_BECAUSE_OF_USER);
        verify(mUpdateMonitor).dispatchKeyguardGoingAway(false);
        verify(mStatusBarKeyguardViewManager, never()).setKeyguardGoingAwayState(anyBoolean());
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void testOnStartedWakingUp_whileSleeping_ifWakeAndUnlocking_doesNotShowKeyguard() {
        when(mLockPatternUtils.isLockScreenDisabled(anyInt())).thenReturn(false);
        when(mLockPatternUtils.getPowerButtonInstantlyLocks(anyInt())).thenReturn(true);
        mViewMediator.onSystemReady();
        TestableLooper.get(this).processAllMessages();

        mViewMediator.setShowingLocked(false);
        TestableLooper.get(this).processAllMessages();

        mViewMediator.onStartedGoingToSleep(OFF_BECAUSE_OF_USER);
        mViewMediator.onWakeAndUnlocking(false);
        mViewMediator.onStartedWakingUp(OFF_BECAUSE_OF_USER, false);
        TestableLooper.get(this).processAllMessages();

        assertFalse(mViewMediator.isShowingAndNotOccluded());
        verify(mKeyguardStateController, never()).notifyKeyguardState(eq(true), anyBoolean());
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void testOnStartedWakingUp_whileSleeping_ifNotWakeAndUnlocking_showsKeyguard() {
        when(mLockPatternUtils.isLockScreenDisabled(anyInt())).thenReturn(false);
        when(mLockPatternUtils.getPowerButtonInstantlyLocks(anyInt())).thenReturn(true);
        mViewMediator.onSystemReady();
        TestableLooper.get(this).processAllMessages();

        mViewMediator.setShowingLocked(false);
        TestableLooper.get(this).processAllMessages();

        mViewMediator.onStartedGoingToSleep(OFF_BECAUSE_OF_USER);
        mViewMediator.onStartedWakingUp(OFF_BECAUSE_OF_USER, false);

        TestableLooper.get(this).processAllMessages();

        assertTrue(mViewMediator.isShowingAndNotOccluded());
    }

    @Test
    public void testRegisterDumpable() {
        verify(mDumpManager).registerDumpable(KeyguardViewMediator.class.getName(), mViewMediator);
        verify(mStatusBarKeyguardViewManager, never()).setKeyguardGoingAwayState(anyBoolean());
    }

    @Test
    public void testKeyguardGone_notGoingaway() {
        mViewMediator.mViewMediatorCallback.keyguardGone();
        verify(mStatusBarKeyguardViewManager).setKeyguardGoingAwayState(eq(false));
    }

    @Test
    public void testIsAnimatingScreenOff() {
        when(mDozeParameters.shouldControlUnlockedScreenOff()).thenReturn(true);
        when(mDozeParameters.shouldAnimateDozingChange()).thenReturn(true);

        mViewMediator.onFinishedGoingToSleep(OFF_BECAUSE_OF_USER, false);
        mViewMediator.setDozing(true);

        // Mid-doze, we should be animating the screen off animation.
        mViewMediator.onDozeAmountChanged(0.5f, 0.5f);
        assertTrue(mViewMediator.isAnimatingScreenOff());

        // Once we're 100% dozed, the screen off animation should be completed.
        mViewMediator.onDozeAmountChanged(1f, 1f);
        assertFalse(mViewMediator.isAnimatingScreenOff());
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void restoreBouncerWhenSimLockedAndKeyguardIsGoingAway() {
        // When showing and provisioned
        mViewMediator.onSystemReady();
        when(mUpdateMonitor.isDeviceProvisioned()).thenReturn(true);
        mViewMediator.setShowingLocked(true);

        // and a SIM becomes locked and requires a PIN
        mViewMediator.mUpdateCallback.onSimStateChanged(
                1 /* subId */,
                0 /* slotId */,
                TelephonyManager.SIM_STATE_PIN_REQUIRED);

        // and the keyguard goes away
        mViewMediator.setShowingLocked(false);
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        mViewMediator.mUpdateCallback.onKeyguardVisibilityChanged(false);

        TestableLooper.get(this).processAllMessages();

        // then make sure it comes back
        verify(mStatusBarKeyguardViewManager, atLeast(1)).show(null);
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void restoreBouncerWhenSimLockedAndKeyguardIsGoingAway_initiallyNotShowing() {
        // When showing and provisioned
        mViewMediator.onSystemReady();
        when(mUpdateMonitor.isDeviceProvisioned()).thenReturn(true);
        mViewMediator.setShowingLocked(false);

        // and a SIM becomes locked and requires a PIN
        mViewMediator.mUpdateCallback.onSimStateChanged(
                1 /* subId */,
                0 /* slotId */,
                TelephonyManager.SIM_STATE_PIN_REQUIRED);

        // and the keyguard goes away
        mViewMediator.setShowingLocked(false);
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        mViewMediator.mUpdateCallback.onKeyguardVisibilityChanged(false);

        TestableLooper.get(this).processAllMessages();

        // then make sure it comes back
        verify(mStatusBarKeyguardViewManager, atLeast(1)).show(null);
    }

    @Test
    public void testBouncerPrompt_deviceLockedByAdmin() {
        // GIVEN no trust agents enabled and biometrics aren't enrolled
        when(mUpdateMonitor.isTrustUsuallyManaged(anyInt())).thenReturn(false);
        when(mUpdateMonitor.isUnlockingWithBiometricsPossible(anyInt())).thenReturn(false);

        // WHEN the strong auth reason is AFTER_DPM_LOCK_NOW
        KeyguardUpdateMonitor.StrongAuthTracker strongAuthTracker =
                mock(KeyguardUpdateMonitor.StrongAuthTracker.class);
        when(mUpdateMonitor.getStrongAuthTracker()).thenReturn(strongAuthTracker);
        when(strongAuthTracker.getStrongAuthForUser(anyInt())).thenReturn(
                STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW);

        // THEN the bouncer prompt reason should return PROMPT_REASON_DEVICE_ADMIN
        assertEquals(KeyguardSecurityView.PROMPT_REASON_DEVICE_ADMIN,
                mViewMediator.mViewMediatorCallback.getBouncerPromptReason());
    }

    @Test
    public void testBouncerPrompt_deviceRestartedDueToMainlineUpdate() {
        // GIVEN biometrics enrolled
        when(mUpdateMonitor.isUnlockingWithBiometricsPossible(anyInt())).thenReturn(true);

        // WHEN reboot caused by ota update
        KeyguardUpdateMonitor.StrongAuthTracker strongAuthTracker =
                mock(KeyguardUpdateMonitor.StrongAuthTracker.class);
        when(mUpdateMonitor.getStrongAuthTracker()).thenReturn(strongAuthTracker);
        when(strongAuthTracker.hasUserAuthenticatedSinceBoot()).thenReturn(false);
        when(mSystemPropertiesHelper.get(SYS_BOOT_REASON_PROP)).thenReturn(REBOOT_MAINLINE_UPDATE);

        // THEN the bouncer prompt reason should return PROMPT_REASON_RESTART_FOR_OTA
        assertEquals(KeyguardSecurityView.PROMPT_REASON_RESTART_FOR_MAINLINE_UPDATE,
                mViewMediator.mViewMediatorCallback.getBouncerPromptReason());
    }

    @Test
    public void testBouncerPrompt_afterUserLockDown() {
        // GIVEN biometrics enrolled
        when(mUpdateMonitor.isUnlockingWithBiometricsPossible(anyInt())).thenReturn(true);

        // WHEN user has locked down the device
        KeyguardUpdateMonitor.StrongAuthTracker strongAuthTracker =
                mock(KeyguardUpdateMonitor.StrongAuthTracker.class);
        when(mUpdateMonitor.getStrongAuthTracker()).thenReturn(strongAuthTracker);
        when(strongAuthTracker.hasUserAuthenticatedSinceBoot()).thenReturn(true);
        when(strongAuthTracker.getStrongAuthForUser(anyInt()))
                .thenReturn(STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);

        // THEN the bouncer prompt reason should return PROMPT_REASON_USER_REQUEST
        assertEquals(KeyguardSecurityView.PROMPT_REASON_USER_REQUEST,
                mViewMediator.mViewMediatorCallback.getBouncerPromptReason());
    }

    @Test
    public void testBouncerPrompt_afterUserLockDown_noBiometricsEnrolled() {
        // GIVEN biometrics not enrolled
        when(mUpdateMonitor.isUnlockingWithBiometricsPossible(anyInt())).thenReturn(false);

        // WHEN user has locked down the device
        KeyguardUpdateMonitor.StrongAuthTracker strongAuthTracker =
                mock(KeyguardUpdateMonitor.StrongAuthTracker.class);
        when(mUpdateMonitor.getStrongAuthTracker()).thenReturn(strongAuthTracker);
        when(strongAuthTracker.hasUserAuthenticatedSinceBoot()).thenReturn(true);
        when(strongAuthTracker.getStrongAuthForUser(anyInt()))
                .thenReturn(STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);

        // THEN the bouncer prompt reason should return the default prompt
        assertEquals(KeyguardSecurityView.PROMPT_REASON_NONE,
                mViewMediator.mViewMediatorCallback.getBouncerPromptReason());
    }

    @Test
    public void testBouncerPrompt_nonStrongIdleTimeout() {
        // GIVEN trust agents enabled and biometrics are enrolled
        when(mUpdateMonitor.isTrustUsuallyManaged(anyInt())).thenReturn(true);
        when(mUpdateMonitor.isUnlockingWithBiometricsPossible(anyInt())).thenReturn(true);

        // WHEN the strong auth reason is STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT
        KeyguardUpdateMonitor.StrongAuthTracker strongAuthTracker =
                mock(KeyguardUpdateMonitor.StrongAuthTracker.class);
        when(mUpdateMonitor.getStrongAuthTracker()).thenReturn(strongAuthTracker);
        when(strongAuthTracker.hasUserAuthenticatedSinceBoot()).thenReturn(true);
        when(strongAuthTracker.isNonStrongBiometricAllowedAfterIdleTimeout(
                anyInt())).thenReturn(false);
        when(strongAuthTracker.getStrongAuthForUser(anyInt())).thenReturn(
                STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT);

        // THEN the bouncer prompt reason should return
        // STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT
        assertEquals(KeyguardSecurityView.PROMPT_REASON_NON_STRONG_BIOMETRIC_TIMEOUT,
                mViewMediator.mViewMediatorCallback.getBouncerPromptReason());
    }

    @Test
    public void testHideSurfaceBehindKeyguardMarksKeyguardNotGoingAway() {
        mViewMediator.hideSurfaceBehindKeyguard();

        verify(mKeyguardStateController).notifyKeyguardGoingAway(false);
    }

    @Test
    public void testUpdateIsKeyguardAfterOccludeAnimationEnds() {
        mViewMediator.mOccludeAnimationController.onLaunchAnimationEnd(
                false /* isExpandingFullyAbove */);

        // Since the updateIsKeyguard call is delayed during the animation, ensure it's called once
        // it ends.
        verify(mCentralSurfaces).updateIsKeyguard();
    }

    @Test
    public void testUpdateIsKeyguardAfterOccludeAnimationIsCancelled() {
        mViewMediator.mOccludeAnimationController.onLaunchAnimationCancelled(
                null /* newKeyguardOccludedState */);

        // Since the updateIsKeyguard call is delayed during the animation, ensure it's called if
        // it's cancelled.
        verify(mCentralSurfaces).updateIsKeyguard();
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void testCancelKeyguardExitAnimation_noPendingLock_keyguardWillNotBeShowing() {
        startMockKeyguardExitAnimation();
        cancelMockKeyguardExitAnimation();

        // There should not be a pending lock, but try to handle it anyway to ensure one isn't set.
        mViewMediator.maybeHandlePendingLock();
        TestableLooper.get(this).processAllMessages();

        assertFalse(mViewMediator.isShowingAndNotOccluded());
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void testCancelKeyguardExitAnimationDueToSleep_withPendingLock_keyguardWillBeShowing() {
        startMockKeyguardExitAnimation();

        mViewMediator.onStartedGoingToSleep(PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON);
        mViewMediator.onFinishedGoingToSleep(PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, false);

        cancelMockKeyguardExitAnimation();

        mViewMediator.maybeHandlePendingLock();
        TestableLooper.get(this).processAllMessages();

        assertTrue(mViewMediator.isShowingAndNotOccluded());
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void testCancelKeyguardExitAnimationThenSleep_withPendingLock_keyguardWillBeShowing() {
        startMockKeyguardExitAnimation();
        cancelMockKeyguardExitAnimation();

        mViewMediator.maybeHandlePendingLock();
        TestableLooper.get(this).processAllMessages();

        mViewMediator.onStartedGoingToSleep(PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON);
        mViewMediator.onFinishedGoingToSleep(PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, false);

        mViewMediator.maybeHandlePendingLock();
        TestableLooper.get(this).processAllMessages();

        assertTrue(mViewMediator.isShowingAndNotOccluded());
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void testStartKeyguardExitAnimation_expectSurfaceBehindRemoteAnimation() {
        startMockKeyguardExitAnimation();
        assertTrue(mViewMediator.isAnimatingBetweenKeyguardAndSurfaceBehind());
    }

    /**
     * Configures mocks appropriately, then starts the keyguard exit animation.
     */
    private void startMockKeyguardExitAnimation() {
        mViewMediator.onSystemReady();
        TestableLooper.get(this).processAllMessages();

        mViewMediator.setShowingLocked(true);

        RemoteAnimationTarget[] apps = new RemoteAnimationTarget[]{
                mock(RemoteAnimationTarget.class)
        };
        RemoteAnimationTarget[] wallpapers = new RemoteAnimationTarget[]{
                mock(RemoteAnimationTarget.class)
        };
        IRemoteAnimationFinishedCallback callback = mock(IRemoteAnimationFinishedCallback.class);

        when(mKeyguardStateController.isKeyguardGoingAway()).thenReturn(true);
        mViewMediator.startKeyguardExitAnimation(TRANSIT_OLD_KEYGUARD_GOING_AWAY, apps, wallpapers,
                null, callback);
        TestableLooper.get(this).processAllMessages();
    }

    /**
     * Configures mocks appropriately, then cancels the keyguard exit animation.
     */
    private void cancelMockKeyguardExitAnimation() {
        when(mKeyguardStateController.isKeyguardGoingAway()).thenReturn(false);
        mViewMediator.cancelKeyguardExitAnimation();
        TestableLooper.get(this).processAllMessages();
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void testKeyguardDelayedOnGoingToSleep_ifScreenOffAnimationWillPlayButIsntPlayingYet() {
        mViewMediator.onSystemReady();
        TestableLooper.get(this).processAllMessages();

        mViewMediator.setShowingLocked(false);
        TestableLooper.get(this).processAllMessages();

        mViewMediator.onStartedGoingToSleep(OFF_BECAUSE_OF_USER);
        TestableLooper.get(this).processAllMessages();

        when(mScreenOffAnimationController.shouldDelayKeyguardShow()).thenReturn(true);
        when(mScreenOffAnimationController.isKeyguardShowDelayed()).thenReturn(false);
        mViewMediator.onFinishedGoingToSleep(OFF_BECAUSE_OF_USER, false);
        TestableLooper.get(this).processAllMessages();

        assertFalse(mViewMediator.isShowingAndNotOccluded());
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void testKeyguardNotDelayedOnGoingToSleep_ifScreenOffAnimationWillNotPlay() {
        mViewMediator.onSystemReady();
        TestableLooper.get(this).processAllMessages();

        mViewMediator.setShowingLocked(false);
        TestableLooper.get(this).processAllMessages();

        mViewMediator.onStartedGoingToSleep(OFF_BECAUSE_OF_USER);
        TestableLooper.get(this).processAllMessages();

        when(mScreenOffAnimationController.shouldDelayKeyguardShow()).thenReturn(false);
        mViewMediator.onFinishedGoingToSleep(OFF_BECAUSE_OF_USER, false);
        TestableLooper.get(this).processAllMessages();

        assertTrue(mViewMediator.isShowingAndNotOccluded());
    }

    @Test
    public void testWakeAndUnlocking() {
        mViewMediator.onWakeAndUnlocking(false);
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(anyBoolean());
    }

    @Test
    public void testWakeAndUnlockingOverDream() {
        // Ensure ordering unlock and wake is enabled.
        createAndStartViewMediator(true);

        // Send signal to wake
        mViewMediator.onWakeAndUnlocking(true);

        // Ensure not woken up yet
        verify(mPowerManager, never()).wakeUp(anyLong(), anyInt(), anyString());

        // Verify keyguard told of authentication
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(anyBoolean());
        mViewMediator.mViewMediatorCallback.keyguardDonePending(true,
                mUpdateMonitor.getCurrentUser());
        mViewMediator.mViewMediatorCallback.readyForKeyguardDone();
        final ArgumentCaptor<Runnable> animationRunnableCaptor =
                ArgumentCaptor.forClass(Runnable.class);
        verify(mStatusBarKeyguardViewManager).startPreHideAnimation(
                animationRunnableCaptor.capture());

        when(mStatusBarStateController.isDreaming()).thenReturn(true);
        when(mStatusBarStateController.isDozing()).thenReturn(false);
        animationRunnableCaptor.getValue().run();

        when(mKeyguardStateController.isShowing()).thenReturn(false);
        mViewMediator.mViewMediatorCallback.keyguardGone();

        // Verify woken up now.
        verify(mPowerManager).wakeUp(anyLong(), anyInt(), anyString());
    }

    @Test
    public void testWakeAndUnlockingOverDream_signalAuthenticateIfStillShowing() {
        // Ensure ordering unlock and wake is enabled.
        createAndStartViewMediator(true);

        // Send signal to wake
        mViewMediator.onWakeAndUnlocking(true);

        // Ensure not woken up yet
        verify(mPowerManager, never()).wakeUp(anyLong(), anyInt(), anyString());

        // Verify keyguard told of authentication
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(anyBoolean());
        clearInvocations(mStatusBarKeyguardViewManager);
        mViewMediator.mViewMediatorCallback.keyguardDonePending(true,
                mUpdateMonitor.getCurrentUser());
        mViewMediator.mViewMediatorCallback.readyForKeyguardDone();
        final ArgumentCaptor<Runnable> animationRunnableCaptor =
                ArgumentCaptor.forClass(Runnable.class);
        verify(mStatusBarKeyguardViewManager).startPreHideAnimation(
                animationRunnableCaptor.capture());

        when(mStatusBarStateController.isDreaming()).thenReturn(true);
        when(mStatusBarStateController.isDozing()).thenReturn(false);
        animationRunnableCaptor.getValue().run();

        when(mKeyguardStateController.isShowing()).thenReturn(true);

        mViewMediator.mViewMediatorCallback.keyguardGone();


        // Verify keyguard view controller informed of authentication again
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(anyBoolean());
    }

    @Test
    public void testWakeAndUnlockingOverNonInteractiveDream_noWakeByKeyguardViewMediator() {
        // Send signal to wake
        mViewMediator.onWakeAndUnlocking(false);

        // Ensure not woken up yet
        verify(mPowerManager, never()).wakeUp(anyLong(), anyInt(), anyString());

        // Verify keyguard told of authentication
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(anyBoolean());
        mViewMediator.mViewMediatorCallback.keyguardDonePending(true,
                mUpdateMonitor.getCurrentUser());
        mViewMediator.mViewMediatorCallback.readyForKeyguardDone();
        final ArgumentCaptor<Runnable> animationRunnableCaptor =
                ArgumentCaptor.forClass(Runnable.class);
        verify(mStatusBarKeyguardViewManager).startPreHideAnimation(
                animationRunnableCaptor.capture());

        when(mStatusBarStateController.isDreaming()).thenReturn(true);
        when(mStatusBarStateController.isDozing()).thenReturn(false);
        animationRunnableCaptor.getValue().run();

        when(mKeyguardStateController.isShowing()).thenReturn(false);
        mViewMediator.mViewMediatorCallback.keyguardGone();

        // Verify not woken up.
        verify(mPowerManager, never()).wakeUp(anyLong(), anyInt(), anyString());
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void testDoKeyguardWhileInteractive_resets() {
        mViewMediator.setShowingLocked(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        TestableLooper.get(this).processAllMessages();

        when(mPowerManager.isInteractive()).thenReturn(true);

        mViewMediator.onSystemReady();
        TestableLooper.get(this).processAllMessages();

        assertTrue(mViewMediator.isShowingAndNotOccluded());
        verify(mStatusBarKeyguardViewManager).reset(false);
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void testDoKeyguardWhileNotInteractive_showsInsteadOfResetting() {
        mViewMediator.setShowingLocked(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        TestableLooper.get(this).processAllMessages();

        when(mPowerManager.isInteractive()).thenReturn(false);

        mViewMediator.onSystemReady();
        TestableLooper.get(this).processAllMessages();

        assertTrue(mViewMediator.isShowingAndNotOccluded());
        verify(mStatusBarKeyguardViewManager, never()).reset(anyBoolean());
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void testNotStartingKeyguardWhenFlagIsDisabled() {
        mViewMediator.setShowingLocked(false);
        when(mKeyguardStateController.isShowing()).thenReturn(false);

        mFeatureFlags.set(Flags.LOCKSCREEN_WITHOUT_SECURE_LOCK_WHEN_DREAMING, false);
        mViewMediator.onDreamingStarted();
        assertFalse(mViewMediator.isShowingAndNotOccluded());
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void testStartingKeyguardWhenFlagIsEnabled() {
        mViewMediator.setShowingLocked(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);

        mFeatureFlags.set(Flags.LOCKSCREEN_WITHOUT_SECURE_LOCK_WHEN_DREAMING, true);
        mViewMediator.onDreamingStarted();
        assertTrue(mViewMediator.isShowingAndNotOccluded());
    }

    @Test
    public void testOnStartedWakingUp_logsUiEvent() {
        final InstanceId instanceId = InstanceId.fakeInstanceId(8);
        when(mSessionTracker.getSessionId((anyInt()))).thenReturn(instanceId);
        mViewMediator.onStartedWakingUp(PowerManager.WAKE_REASON_LIFT, false);

        verify(mUiEventLogger).logWithInstanceIdAndPosition(
                eq(BiometricUnlockController.BiometricUiEvent.STARTED_WAKING_UP),
                anyInt(),
                any(),
                eq(instanceId),
                eq(PowerManager.WAKE_REASON_LIFT)
        );
    }

    @Test
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    public void pendingPinLockOnKeyguardGoingAway_doKeyguardLockedOnKeyguardVisibilityChanged() {
        // GIVEN SIM_STATE_PIN_REQUIRED
        mViewMediator.onSystemReady();
        final KeyguardUpdateMonitorCallback keyguardUpdateMonitorCallback =
                mViewMediator.mUpdateCallback;
        keyguardUpdateMonitorCallback.onSimStateChanged(0, 0,
                TelephonyManager.SIM_STATE_PIN_REQUIRED);
        TestableLooper.get(this).processAllMessages();

        // ...and then the primary bouncer shows while the keyguard is going away
        captureKeyguardStateControllerCallback();
        when(mKeyguardStateController.isPrimaryBouncerShowing()).thenReturn(true);
        when(mKeyguardStateController.isKeyguardGoingAway()).thenReturn(true);
        mKeyguardStateControllerCallback.getValue().onPrimaryBouncerShowingChanged();
        TestableLooper.get(this).processAllMessages();

        // WHEN keyguard visibility becomes FALSE
        mViewMediator.setShowingLocked(false);
        keyguardUpdateMonitorCallback.onKeyguardVisibilityChanged(false);
        TestableLooper.get(this).processAllMessages();

        // THEN keyguard shows due to the pending SIM PIN lock
        assertTrue(mViewMediator.isShowingAndNotOccluded());
    }

    @Test
    public void testBouncerSwipeDown() {
        mViewMediator.getViewMediatorCallback().onBouncerSwipeDown();
        verify(mStatusBarKeyguardViewManager).reset(true);
    }
    private void createAndStartViewMediator() {
        createAndStartViewMediator(false);
    }

    private void createAndStartViewMediator(boolean orderUnlockAndWake) {
        if (orderUnlockAndWake) {
            mContext.getOrCreateTestableResources().addOverride(
                    com.android.internal.R.bool.config_orderUnlockAndWake, orderUnlockAndWake);
        }

        mViewMediator = new KeyguardViewMediator(
                mContext,
                mUiEventLogger,
                mSessionTracker,
                mUserTracker,
                mFalsingCollector,
                mLockPatternUtils,
                mBroadcastDispatcher,
                () -> mStatusBarKeyguardViewManager,
                mDismissCallbackRegistry,
                mUpdateMonitor,
                mDumpManager,
                mUiBgExecutor,
                mPowerManager,
                mTrustManager,
                mUserSwitcherController,
                mDeviceConfig,
                mNavigationModeController,
                mKeyguardDisplayManager,
                mDozeParameters,
                mStatusBarStateController,
                mKeyguardStateController,
                () -> mKeyguardUnlockAnimationController,
                mScreenOffAnimationController,
                () -> mNotificationShadeDepthController,
                mScreenOnCoordinator,
                mKeyguardTransitions,
                mInteractionJankMonitor,
                mDreamOverlayStateController,
                () -> mShadeController,
                () -> mNotificationShadeWindowController,
                () -> mActivityLaunchAnimator,
                () -> mScrimController,
                mFeatureFlags,
                mDispatcher,
                () -> mDreamingToLockscreenTransitionViewModel,
                mSystemPropertiesHelper);
        mViewMediator.start();

        mViewMediator.registerCentralSurfaces(mCentralSurfaces, null, null, null, null, null);
    }

    private void captureKeyguardUpdateMonitorCallback() {
        verify(mUpdateMonitor).registerCallback(mKeyguardUpdateMonitorCallbackCaptor.capture());
    }

    private void captureKeyguardStateControllerCallback() {
        verify(mKeyguardStateController).addCallback(mKeyguardStateControllerCallback.capture());
    }
}
