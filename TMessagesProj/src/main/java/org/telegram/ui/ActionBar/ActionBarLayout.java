/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.ActionBar;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AnimationNotificationsLocker;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.BackButtonMenu;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.ChatAttachAlert;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FloatingDebug.FloatingDebugController;
import org.telegram.ui.Components.FloatingDebug.FloatingDebugProvider;
import org.telegram.ui.Components.GroupCallPip;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.EmptyBaseFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stories.StoryViewer;
import org.telegram.ui.bots.BotWebViewSheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.utils.VibrateUtil;
import xyz.nextalone.nagram.NaConfig;

public class ActionBarLayout extends FrameLayout implements INavigationLayout, FloatingDebugProvider {

    public boolean highlightActionButtons = false;
    private boolean attached;
    private boolean isSheet;
    private Window window;

    @Override
    public void setHighlightActionButtons(boolean highlightActionButtons) {
        this.highlightActionButtons = highlightActionButtons;
    }

    public boolean storyViewerAttached() {
        BaseFragment lastFragment = null;
        if (!fragmentsStack.isEmpty()) {
            lastFragment = fragmentsStack.get(fragmentsStack.size() - 1);
        }
        return lastFragment != null && lastFragment.getLastStoryViewer() != null && lastFragment.getLastStoryViewer().attachedToParent();
    }

    public class LayoutContainer extends FrameLayout {

        private Rect rect = new Rect();
        private boolean isKeyboardVisible;

        private int fragmentPanTranslationOffset;
        private Paint backgroundPaint = new Paint();
        private int backgroundColor;

        private boolean wasPortrait;

        public LayoutContainer(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        @Override
        protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
            BaseFragment lastFragment = null;
            if (!fragmentsStack.isEmpty()) {
                lastFragment = fragmentsStack.get(fragmentsStack.size() - 1);
            }
            if (sheetFragment != null && sheetFragment.sheetsStack != null && !sheetFragment.sheetsStack.isEmpty()) {
                lastFragment = sheetFragment;
            }
            BaseFragment.AttachedSheet lastSheet = null;
            if (lastFragment != null) lastSheet = lastFragment.getLastSheet();
            if (lastSheet != null && lastSheet.isFullyVisible() && lastSheet.getWindowView() != child) {
                return true;
            }
            if (child instanceof ActionBar) {
                return super.drawChild(canvas, child, drawingTime);
            } else {
                int actionBarHeight = 0;
                int actionBarY = 0;
                int childCount = getChildCount();
                for (int a = 0; a < childCount; a++) {
                    View view = getChildAt(a);
                    if (view == child) {
                        continue;
                    }
                    if (view instanceof ActionBar && view.getVisibility() == VISIBLE) {
                        if (((ActionBar) view).getCastShadows()) {
                            actionBarHeight = view.getMeasuredHeight();
                            actionBarY = (int) view.getY();
                        }
                        break;
                    }
                }
                boolean result = super.drawChild(canvas, child, drawingTime);
                if (actionBarHeight != 0 && headerShadowDrawable != null) {
                    headerShadowDrawable.setBounds(0, actionBarY + actionBarHeight, getMeasuredWidth(), actionBarY + actionBarHeight + headerShadowDrawable.getIntrinsicHeight());
                    headerShadowDrawable.draw(canvas);
                }
                return result;
            }
        }

        @Override
        public boolean hasOverlappingRendering() {
            if (Build.VERSION.SDK_INT >= 28) {
                return true;
            }
            return false;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            boolean isPortrait = height > width;
            if (wasPortrait != isPortrait && isInPreviewMode()) {
                finishPreviewFragment();
            }
            wasPortrait = isPortrait;

            int count = getChildCount();
            int actionBarHeight = 0;

            View rootView = getRootView();
            getWindowVisibleDisplayFrame(rect);
            int usableViewHeight = rootView.getHeight() - (rect.top != 0 ? AndroidUtilities.statusBarHeight : 0) - AndroidUtilities.getViewInset(rootView);
            boolean isKeyboardVisible = usableViewHeight - (rect.bottom - rect.top) > 0;

            if (bottomSheetTabs != null) {
                bottomSheetTabs.updateCurrentAccount();
            }
            final int bottomTabsHeight = isKeyboardVisible ? 0 : getBottomTabsHeight(false);

            for (int a = 0; a < count; a++) {
                View child = getChildAt(a);
                if (child instanceof ActionBar) {
                    child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.UNSPECIFIED));
                    actionBarHeight = child.getMeasuredHeight();
                    break;
                }
            }
            for (int a = 0; a < count; a++) {
                View child = getChildAt(a);
                if (!(child instanceof ActionBar)) {
                    if (child.getFitsSystemWindows() || child instanceof BaseFragment.AttachedSheetWindow) {
                        measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, bottomTabsHeight);
                    } else {
                        measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, actionBarHeight + bottomTabsHeight);
                    }
                }
            }
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int count = getChildCount();
            int actionBarHeight = 0;
            for (int a = 0; a < count; a++) {
                View child = getChildAt(a);
                if (child instanceof ActionBar) {
                    actionBarHeight = child.getMeasuredHeight();
                    child.layout(0, 0, child.getMeasuredWidth(), actionBarHeight);
                    break;
                }
            }
            for (int a = 0; a < count; a++) {
                View child = getChildAt(a);
                if (!(child instanceof ActionBar)) {
                    FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) child.getLayoutParams();
                    if (child.getFitsSystemWindows() || child instanceof BaseFragment.AttachedSheetWindow) {
                        child.layout(
                            layoutParams.leftMargin,
                            layoutParams.topMargin,
                            layoutParams.leftMargin + child.getMeasuredWidth(),
                            layoutParams.topMargin + child.getMeasuredHeight()
                        );
                    } else {
                        child.layout(
                            layoutParams.leftMargin,
                            layoutParams.topMargin + actionBarHeight,
                            layoutParams.leftMargin + child.getMeasuredWidth(),
                            layoutParams.topMargin + actionBarHeight + child.getMeasuredHeight()
                        );
                    }
                }
            }

            View rootView = getRootView();
            getWindowVisibleDisplayFrame(rect);
            int usableViewHeight = rootView.getHeight() - (rect.top != 0 ? AndroidUtilities.statusBarHeight : 0) - AndroidUtilities.getViewInset(rootView);
            isKeyboardVisible = usableViewHeight - (rect.bottom - rect.top) > 0;
            if (waitingForKeyboardCloseRunnable != null && !containerView.isKeyboardVisible && !containerViewBack.isKeyboardVisible) {
                AndroidUtilities.cancelRunOnUIThread(waitingForKeyboardCloseRunnable);
                waitingForKeyboardCloseRunnable.run();
                waitingForKeyboardCloseRunnable = null;
            }
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                final int bottomSheetHeight = isKeyboardVisible ? 0 : getBottomTabsHeight(true);
                if (ev.getY() > getHeight() - bottomSheetHeight) {
                    return false;
                }
            }
//            processMenuButtonsTouch(ev);
            boolean passivePreview = inPreviewMode && previewMenu == null;
            if ((passivePreview || transitionAnimationPreviewMode) && (ev.getActionMasked() == MotionEvent.ACTION_DOWN || ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN)) {
                return false;
            }
            try {
                return (!passivePreview || this != containerView) && super.dispatchTouchEvent(ev);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            return false;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (fragmentPanTranslationOffset != 0) {
                int color = Theme.getColor(Theme.key_windowBackgroundWhite);
                if (backgroundColor != color) {
                    backgroundPaint.setColor(backgroundColor = Theme.getColor(Theme.key_windowBackgroundWhite));
                }
                canvas.drawRect(0, getMeasuredHeight() - fragmentPanTranslationOffset - 3, getMeasuredWidth(), getMeasuredHeight(), backgroundPaint);
            }
            super.onDraw(canvas);
        }

        public void setFragmentPanTranslationOffset(int fragmentPanTranslationOffset) {
            this.fragmentPanTranslationOffset = fragmentPanTranslationOffset;
            invalidate();
        }

        float lastY, startY;
        // for menu buttons to be clicked by hover:
        private float pressX, pressY;
        private boolean allowToPressByHover;
        public void processMenuButtonsTouch(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                startY = event.getY();
            }
            if (isInPreviewMode() && previewMenu == null) {
                lastY = event.getY();
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    finishPreviewFragment();
                } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float dy = startY - lastY;
                    movePreviewFragment(dy);
                    if (dy < 0) {
                        startY = lastY;
                    }
                }
                return;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                pressX = event.getX();
                pressY = event.getY();
                allowToPressByHover = false;
            } else if (event.getAction() == MotionEvent.ACTION_MOVE || event.getAction() == MotionEvent.ACTION_UP) {
                if (previewMenu != null && highlightActionButtons) {
//                    movePreviewFragment(Math.min(pressY, AndroidUtilities.displaySize.y * .4f) - event.getY());
                    if (!allowToPressByHover && Math.sqrt(Math.pow(pressX - event.getX(), 2) + Math.pow(pressY - event.getY(), 2)) > dp(30)) {
                        allowToPressByHover = true;
                    }
                    if (allowToPressByHover && (previewMenu.getSwipeBack() == null || !previewMenu.getSwipeBack().isForegroundOpen())) {
                        for (int i = 0; i < previewMenu.getItemsCount(); ++i) {
                            ActionBarMenuSubItem button = (ActionBarMenuSubItem) previewMenu.getItemAt(i);
                            if (button != null) {
                                Drawable ripple = button.getBackground();
                                button.getGlobalVisibleRect(AndroidUtilities.rectTmp2);
                                boolean shouldBeEnabled = AndroidUtilities.rectTmp2.contains((int) event.getX(), (int) event.getY()),
                                        enabled = ripple.getState().length == 2;
                                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                                    if (shouldBeEnabled != enabled) {
                                        ripple.setState(shouldBeEnabled ? new int[]{android.R.attr.state_pressed, android.R.attr.state_enabled} : new int[]{});
                                        if (shouldBeEnabled) {
                                            AndroidUtilities.vibrateCursor(button);
                                        }
                                    }
                                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                                    if (shouldBeEnabled) {
                                        button.performClick();
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (previewMenu != null && highlightActionButtons) {
                    int alpha = 255;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        alpha = Theme.moveUpDrawable.getAlpha();
                    }
                    ValueAnimator arrowAlphaUpdate = ValueAnimator.ofFloat(alpha, 0);
                    arrowAlphaUpdate.addUpdateListener(a -> {
                        Theme.moveUpDrawable.setAlpha(((Float) a.getAnimatedValue()).intValue());
                        if (drawerLayoutContainer != null) {
                            drawerLayoutContainer.invalidate();
                        }
                        if (containerView != null) {
                            containerView.invalidate();
                        }
                        ActionBarLayout.this.invalidate();
                    });
                    arrowAlphaUpdate.setDuration(150);
                    arrowAlphaUpdate.setInterpolator(CubicBezierInterpolator.DEFAULT);
                    arrowAlphaUpdate.start();
                    ObjectAnimator containerTranslationUpdate = ObjectAnimator.ofFloat(containerView, View.TRANSLATION_Y, 0);
                    containerTranslationUpdate.setDuration(150);
                    containerTranslationUpdate.setInterpolator(CubicBezierInterpolator.DEFAULT);
                    containerTranslationUpdate.start();
                }
                highlightActionButtons = false;
            }
        }
    }

    @Override
    public boolean allowSwipe() {
        return (sheetFragment == null || sheetFragment.getLastSheet() == null || !sheetFragment.getLastSheet().isShown());
    }

    public static Drawable headerShadowDrawable;
    private static Drawable layerShadowDrawable;
    private static Paint scrimPaint;

    private Runnable waitingForKeyboardCloseRunnable;
    private Runnable delayedOpenAnimationRunnable;

    private boolean inBubbleMode;

    private boolean inPreviewMode;
    private boolean previewOpenAnimationInProgress;
    private ColorDrawable previewBackgroundDrawable;

    public LayoutContainer containerView;
    public LayoutContainer containerViewBack;
    public LayoutContainer sheetContainer;
    private DrawerLayoutContainer drawerLayoutContainer;
    private ActionBar currentActionBar;
    private BottomSheetTabs bottomSheetTabs;
    private BottomSheetTabs.ClipTools bottomSheetTabsClip;

    private EmptyBaseFragment sheetFragment;
    public EmptyBaseFragment getSheetFragment() {
        return getSheetFragment(true);
    }
    public EmptyBaseFragment getSheetFragment(boolean create) {
        if (parentActivity == null)
            return null;
        if (sheetFragment == null) {
            sheetFragment = new EmptyBaseFragment();
            sheetFragment.setParentLayout(this);
            View fragmentView = sheetFragment.fragmentView;
            if (fragmentView == null) {
                fragmentView = sheetFragment.createView(parentActivity);
            }
            if (fragmentView.getParent() != sheetContainer) {
                AndroidUtilities.removeFromParent(fragmentView);
                sheetContainer.addView(fragmentView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            }
            sheetFragment.onResume();
            sheetFragment.onBecomeFullyVisible();
        }
        return sheetFragment;
    }

    private BaseFragment newFragment;
    private BaseFragment oldFragment;

    /* Contest */
    private ActionBarPopupWindow.ActionBarPopupWindowLayout previewMenu;

    private AnimatorSet currentAnimation;
    private DecelerateInterpolator decelerateInterpolator = new DecelerateInterpolator(1.5f);
    private OvershootInterpolator overshootInterpolator = new OvershootInterpolator(1.02f);
    private AccelerateDecelerateInterpolator accelerateDecelerateInterpolator = new AccelerateDecelerateInterpolator();

    public float innerTranslationX;

    private boolean maybeStartTracking;
    protected boolean startedTracking;
    private int startedTrackingX;
    private int startedTrackingY;
    protected boolean animationInProgress;
    private VelocityTracker velocityTracker;
    private View layoutToIgnore;
    private boolean beginTrackingSent;
    private boolean transitionAnimationInProgress;
    private boolean transitionAnimationPreviewMode;
    private ArrayList<int[]> animateStartColors = new ArrayList<>();
    private ArrayList<int[]> animateEndColors = new ArrayList<>();

    StartColorsProvider startColorsProvider = new StartColorsProvider();
    public Theme.MessageDrawable messageDrawableOutStart;
    public Theme.MessageDrawable messageDrawableOutMediaStart;
    public ThemeAnimationSettings.onAnimationProgress animationProgressListener;

    private ArrayList<ArrayList<ThemeDescription>> themeAnimatorDescriptions = new ArrayList<>();
    private ArrayList<ThemeDescription> presentingFragmentDescriptions;
    private ArrayList<ThemeDescription.ThemeDescriptionDelegate> themeAnimatorDelegate = new ArrayList<>();
    private AnimatorSet themeAnimatorSet;
    AnimationNotificationsLocker notificationsLocker = new AnimationNotificationsLocker();
    private float themeAnimationValue;
    private boolean animateThemeAfterAnimation;
    private Theme.ThemeInfo animateSetThemeAfterAnimation;
    private boolean animateSetThemeAfterAnimationApply;
    private boolean animateSetThemeNightAfterAnimation;
    private int animateSetThemeAccentIdAfterAnimation;
    private boolean rebuildAfterAnimation;
    private boolean rebuildLastAfterAnimation;
    private boolean showLastAfterAnimation;
    private long transitionAnimationStartTime;
    private boolean inActionMode;
    private int startedTrackingPointerId;
    private Runnable onCloseAnimationEndRunnable;
    private Runnable onOpenAnimationEndRunnable;
    private boolean useAlphaAnimations;
    private View backgroundView;
    private boolean removeActionBarExtraHeight;
    private Runnable animationRunnable;

    private float animationProgress;
    private long lastFrameTime;

    private String titleOverlayText;
    private int titleOverlayTextId;
    private Runnable overlayAction;

    private INavigationLayoutDelegate delegate;
    protected Activity parentActivity;
    private final boolean main;

    private List<BaseFragment> fragmentsStack;
    private List<BackButtonMenu.PulledDialog> pulledDialogs;
    private Rect rect = new Rect();
    private boolean delayedAnimationResumed;
    private Runnable onFragmentStackChangedListener;

    private int overrideWidthOffset = -1;

    public ActionBarLayout(Context context, boolean main) {
        super(context);
        parentActivity = (Activity) context;
        this.main = main;

        if (layerShadowDrawable == null) {
            layerShadowDrawable = getResources().getDrawable(R.drawable.layer_shadow);
            headerShadowDrawable = NekoConfig.disableAppBarShadow.Bool() ? null : getResources().getDrawable(R.drawable.header_shadow).mutate();
            scrimPaint = new Paint();
        }

        if (USE_ACTIONBAR_CROSSFADE) {
            setWillNotDraw(false);
            menuDrawable = new MenuDrawable(MenuDrawable.TYPE_DEFAULT);
            menuDrawable.setRoundCap();
        }
    }

    @Override
    public void setFragmentStack(List<BaseFragment> stack) {
        this.fragmentsStack = stack;

        if (bottomSheetTabs != null) {
            bottomSheetTabs.stopListening(this::invalidate, this::relayout);
            AndroidUtilities.removeFromParent(bottomSheetTabs);
            bottomSheetTabs = null;
        }

        LayoutParams layoutParams;
        if (main) {
            bottomSheetTabs = new BottomSheetTabs(parentActivity, this);
            bottomSheetTabsClip = new BottomSheetTabs.ClipTools(bottomSheetTabs);
            bottomSheetTabs.listen(this::invalidate, this::relayout);
            layoutParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68 + 8));
            layoutParams.gravity = Gravity.BOTTOM | Gravity.FILL_HORIZONTAL;
            addView(bottomSheetTabs, layoutParams);

            if (LaunchActivity.instance.getBottomSheetTabsOverlay() != null) {
                LaunchActivity.instance.getBottomSheetTabsOverlay().setTabsView(bottomSheetTabs);
            }
        }

        if (containerViewBack != null) {
            AndroidUtilities.removeFromParent(containerViewBack);
        }
        this.containerViewBack = new LayoutContainer(parentActivity);
        addView(containerViewBack);
        layoutParams = (LayoutParams) containerViewBack.getLayoutParams();
        layoutParams.width = LayoutHelper.MATCH_PARENT;
        layoutParams.height = LayoutHelper.MATCH_PARENT;
        layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
        containerViewBack.setLayoutParams(layoutParams);

        if (containerView != null) {
            AndroidUtilities.removeFromParent(containerView);
        }
        containerView = new LayoutContainer(parentActivity);
        addView(containerView);
        layoutParams = (LayoutParams) containerView.getLayoutParams();
        layoutParams.width = LayoutHelper.MATCH_PARENT;
        layoutParams.height = LayoutHelper.MATCH_PARENT;
        layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
        containerView.setLayoutParams(layoutParams);

        if (sheetContainer != null) {
            AndroidUtilities.removeFromParent(sheetContainer);
        }
        sheetContainer = new LayoutContainer(parentActivity);
        addView(sheetContainer);
        layoutParams = (LayoutParams) sheetContainer.getLayoutParams();
        layoutParams.width = LayoutHelper.MATCH_PARENT;
        layoutParams.height = LayoutHelper.MATCH_PARENT;
        layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
        sheetContainer.setLayoutParams(layoutParams);
        if (sheetFragment != null) {
            sheetFragment.setParentLayout(this);
            View fragmentView = sheetFragment.fragmentView;
            if (fragmentView == null) {
                fragmentView = sheetFragment.createView(parentActivity);
            }
            if (fragmentView.getParent() != sheetContainer) {
                AndroidUtilities.removeFromParent(fragmentView);
                sheetContainer.addView(fragmentView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            }
            sheetFragment.onResume();
            sheetFragment.onBecomeFullyVisible();
        }

        for (BaseFragment fragment : fragmentsStack) {
            fragment.setParentLayout(this);
        }
    }

    @Override
    public void setIsSheet(boolean isSheet) {
        this.isSheet = isSheet;
    }

    @Override
    public boolean isSheet() {
        return isSheet;
    }

    @Override
    public void updateTitleOverlay() {
        BaseFragment fragment = getLastFragment();
        if (fragment != null && fragment.actionBar != null) {
            fragment.actionBar.setTitleOverlayText(titleOverlayText, titleOverlayTextId, overlayAction);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!fragmentsStack.isEmpty()) {
            for (int a = 0, N = fragmentsStack.size(); a < N; a++) {
                BaseFragment fragment = fragmentsStack.get(a);
                fragment.onConfigurationChanged(newConfig);
                if (fragment.visibleDialog instanceof BottomSheet) {
                    ((BottomSheet) fragment.visibleDialog).onConfigurationChanged(newConfig);
                }
            }
        }
    }

    public boolean isKeyboardVisible;
    private int[] measureSpec = new int[2];
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        BaseFragment lastFragment = null;
        if (!fragmentsStack.isEmpty()) {
            lastFragment = fragmentsStack.get(fragmentsStack.size() - 1);
        }
        if (lastFragment != null && storyViewerAttached()) {
            //remeasure only storyViewer if keyboard visibility changed
            int keyboardHeight = measureKeyboardHeight();
            lastFragment.setKeyboardHeightFromParent(keyboardHeight);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec) + keyboardHeight, MeasureSpec.EXACTLY));
            return;
        }
        if (delegate != null) {
            measureSpec[0] = widthMeasureSpec;
            measureSpec[1] = heightMeasureSpec;
            delegate.onMeasureOverride(measureSpec);
            widthMeasureSpec = measureSpec[0];
            heightMeasureSpec = measureSpec[1];
        }
        isKeyboardVisible = measureKeyboardHeight() > dp(20);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private int savedBottomSheetTabsTop;
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int count = getChildCount();

        final int parentLeft = getPaddingLeft();
        final int parentRight = right - left - getPaddingRight();

        final int parentTop = getPaddingTop();
        final int parentBottom = bottom - top - getPaddingBottom();

        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                if (child == bottomSheetTabs) {
                    bottomSheetTabs.updateCurrentAccount();
                }

                final LayoutParams lp = (LayoutParams) child.getLayoutParams();

                final int width = child.getMeasuredWidth();
                final int height = child.getMeasuredHeight();

                int childLeft;
                int childTop;

                int gravity = lp.gravity;
                if (gravity == -1) {
                    gravity = Gravity.TOP | Gravity.START;
                }

                final int layoutDirection = getLayoutDirection();
                final int absoluteGravity = Gravity.getAbsoluteGravity(gravity, layoutDirection);
                final int verticalGravity = gravity & Gravity.VERTICAL_GRAVITY_MASK;

                switch (absoluteGravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
                    case Gravity.CENTER_HORIZONTAL:
                        childLeft = parentLeft + (parentRight - parentLeft - width) / 2 +
                                lp.leftMargin - lp.rightMargin;
                        break;
                    case Gravity.RIGHT:
                        childLeft = parentRight - width - lp.rightMargin;
                        break;
                    case Gravity.LEFT:
                    default:
                        childLeft = parentLeft + lp.leftMargin;
                }

                switch (verticalGravity) {
                    case Gravity.TOP:
                        childTop = parentTop + lp.topMargin;
                        break;
                    case Gravity.CENTER_VERTICAL:
                        childTop = parentTop + (parentBottom - parentTop - height) / 2 +
                                lp.topMargin - lp.bottomMargin;
                        break;
                    case Gravity.BOTTOM:
                        childTop = parentBottom - height - lp.bottomMargin;
                        break;
                    default:
                        childTop = parentTop + lp.topMargin;
                }

                if (child == bottomSheetTabs && savedBottomSheetTabsTop != 0 && (isKeyboardVisible || getParent() instanceof View && ((View) getParent()).getHeight() > getHeight())) {
                    childTop = savedBottomSheetTabsTop;
                } else if (child == bottomSheetTabs) {
                    savedBottomSheetTabsTop = childTop;
                }
                child.layout(childLeft, childTop, childLeft + width, childTop + height);
            }
        }
    }

    @Override
    public void setInBubbleMode(boolean value) {
        inBubbleMode = value;
    }

    @Override
    public boolean isInBubbleMode() {
        return inBubbleMode;
    }

    @Override
    public void drawHeaderShadow(Canvas canvas, int alpha, int y) {
        if (headerShadowDrawable != null && SharedConfig.drawActionBarShadow) {
            alpha = alpha / 2;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                if (headerShadowDrawable.getAlpha() != alpha) {
                    headerShadowDrawable.setAlpha(alpha);
                }
            } else {
                headerShadowDrawable.setAlpha(alpha);
            }
            headerShadowDrawable.setBounds(0, y, getMeasuredWidth(), y + headerShadowDrawable.getIntrinsicHeight());
            headerShadowDrawable.draw(canvas);
        }
    }

    @Keep
    public void setInnerTranslationX(float value) {
        innerTranslationX = value;
        invalidate();

        if (fragmentsStack.size() >= 2 && containerView.getMeasuredWidth() > 0) {
            float progress = value / containerView.getMeasuredWidth();
            BaseFragment prevFragment = fragmentsStack.get(fragmentsStack.size() - 2);
            prevFragment.onSlideProgress(false, progress);
            BaseFragment currFragment = fragmentsStack.get(fragmentsStack.size() - 1);
            float ratio = MathUtils.clamp(2f * progress, 0f, 1f);
            if (currFragment.isBeginToShow()) {
                int currNavigationBarColor = currFragment.getNavigationBarColor();
                int prevNavigationBarColor = prevFragment.getNavigationBarColor();
                if (currNavigationBarColor != prevNavigationBarColor) {
                    currFragment.setNavigationBarColor(ColorUtils.blendARGB(currNavigationBarColor, prevNavigationBarColor, ratio));
                }
            }
            if (currFragment != null && !currFragment.inPreviewMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !SharedConfig.noStatusBar) {
                int overlayColor = ColorUtils.calculateLuminance(Theme.getColor(Theme.key_actionBarDefault)) > 0.7f ? AndroidUtilities.LIGHT_STATUS_BAR_OVERLAY : AndroidUtilities.DARK_STATUS_BAR_OVERLAY;
                int oldStatusBarColor = prevFragment != null && prevFragment.hasForceLightStatusBar() ? Color.TRANSPARENT : overlayColor;
                int newStatusBarColor = currFragment != null && currFragment.hasForceLightStatusBar() ? Color.TRANSPARENT : overlayColor;
                parentActivity.getWindow().setStatusBarColor(ColorUtils.blendARGB(newStatusBarColor, oldStatusBarColor, ratio));
            }
        }
    }

    @Keep
    public float getInnerTranslationX() {
        return innerTranslationX;
    }

    @Override
    public void onResume() {
//        if (transitionAnimationInProgress) {
//            if (currentAnimation != null) {
//                currentAnimation.cancel();
//                currentAnimation = null;
//            }
//            if (animationRunnable != null) {
//                AndroidUtilities.cancelRunOnUIThread(animationRunnable);
//                animationRunnable = null;
//            }
//            if (waitingForKeyboardCloseRunnable != null) {
//                AndroidUtilities.cancelRunOnUIThread(waitingForKeyboardCloseRunnable);
//                waitingForKeyboardCloseRunnable = null;
//            }
//            if (onCloseAnimationEndRunnable != null) {
//                onCloseAnimationEnd();
//            } else if (onOpenAnimationEndRunnable != null) {
//                onOpenAnimationEnd();
//            }
//        }
        if (!fragmentsStack.isEmpty()) {
            BaseFragment lastFragment = fragmentsStack.get(fragmentsStack.size() - 1);
            lastFragment.onResume();
        }
        if (sheetFragment != null) {
            sheetFragment.onResume();
        }
    }

    @Override
    public void onUserLeaveHint() {
        if (!fragmentsStack.isEmpty()) {
            BaseFragment lastFragment = fragmentsStack.get(fragmentsStack.size() - 1);
            lastFragment.onUserLeaveHint();
        }
        if (sheetFragment != null) {
            sheetFragment.onUserLeaveHint();
        }
    }

    @Override
    public void onPause() {
        if (!fragmentsStack.isEmpty()) {
            BaseFragment lastFragment = fragmentsStack.get(fragmentsStack.size() - 1);
            lastFragment.onPause();
        }
        if (sheetFragment != null) {
            sheetFragment.onPause();
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return animationInProgress || checkTransitionAnimation() || onTouchEvent(ev);
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        onTouchEvent(null);
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }

    @Override
    public boolean dispatchKeyEventPreIme(KeyEvent event) {
        if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            return delegate != null && delegate.onPreIme() || super.dispatchKeyEventPreIme(event);
        }
        return super.dispatchKeyEventPreIme(event);
    }

    private boolean withShadow;

    @Override
    protected void dispatchDraw(Canvas canvas) {
        withShadow = true;
        super.dispatchDraw(canvas);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (drawerLayoutContainer != null && drawerLayoutContainer.isDrawCurrentPreviewFragmentAbove()) {
            if (inPreviewMode || transitionAnimationPreviewMode || previewOpenAnimationInProgress) {
                if (child == (oldFragment != null && oldFragment.inPreviewMode ? containerViewBack : containerView)) {
                    drawerLayoutContainer.invalidate();
                    return false;
                }
            }
        }

        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int translationX = (int) innerTranslationX + getPaddingRight();
        int clipLeft = getPaddingLeft();
        int clipRight = width + getPaddingLeft();

        if (child == containerViewBack) {
            clipRight = translationX + dp(1);
        } else if (child == containerView) {
            clipLeft = translationX;
        }

        final int restoreCount2 = canvas.save();
        if (child != bottomSheetTabs && bottomSheetTabsClip != null) {
            bottomSheetTabsClip.clip(canvas, withShadow, isKeyboardVisible, getWidth(), (int) getY() + getHeight(), 1.0f);
            withShadow = false;
        }

        final int restoreCount = canvas.save();
        if (!isTransitionAnimationInProgress() && !inPreviewMode) {
            canvas.clipRect(clipLeft, 0, clipRight, getHeight());
        }
        if ((inPreviewMode || transitionAnimationPreviewMode) && child == containerView) {
            drawPreviewDrawables(canvas, containerView);
        }
        final boolean result = super.drawChild(canvas, child, drawingTime);
        canvas.restoreToCount(restoreCount);

        if (translationX != 0 || overrideWidthOffset != -1) {
            int widthOffset = overrideWidthOffset != -1 ? overrideWidthOffset : width - translationX;
            int top = 0;
            if (isActionBarInCrossfade()) {
                top += getPaddingTop();
                top += AndroidUtilities.lerp(getBackgroundFragment().getActionBar().getHeight(), getLastFragment().getActionBar().getHeight(), 1f - widthOffset / (float) width);
            }
            if (child == containerView) {
                float alpha = USE_SPRING_ANIMATION ? MathUtils.clamp(widthOffset / (float) width, 0, 1.0f) : MathUtils.clamp(widthOffset / (float) dp(20), 0, 1f);
                layerShadowDrawable.setBounds(translationX - layerShadowDrawable.getIntrinsicWidth(), top + child.getTop(), translationX, child.getBottom() - getBottomTabsHeight(true));
                layerShadowDrawable.setAlpha((int) (0xff * alpha));
                layerShadowDrawable.draw(canvas);
            } else if (child == containerViewBack) {
                float opacity = MathUtils.clamp(widthOffset / (float) width, 0, 0.8f);
                scrimPaint.setColor(Color.argb((int) ((USE_SPRING_ANIMATION ? USE_ACTIONBAR_CROSSFADE ? 0x29 : 0x7a : 0x99) * opacity), 0x00, 0x00, 0x00));
                if (overrideWidthOffset != -1) {
                    canvas.drawRect(0, top, getWidth(), getHeight() * 1.5f, scrimPaint);
                } else {
                    canvas.drawRect(clipLeft, top, clipRight, getHeight() * 1.5f, scrimPaint);
                }
            }
        }
        canvas.restoreToCount(restoreCount2);

        return result;
    }

    public void parentDraw(View parent, Canvas canvas) {
        if (bottomSheetTabs != null && getHeight() < parent.getHeight()) {
            canvas.save();
            canvas.translate(getX() + bottomSheetTabs.getX(), getY() + bottomSheetTabs.getY());
            bottomSheetTabs.draw(canvas);
            canvas.restore();
        }
    }

    public void setOverrideWidthOffset(int overrideWidthOffset) {
        this.overrideWidthOffset = overrideWidthOffset;
        invalidate();
    }

    @Override
    public float getCurrentPreviewFragmentAlpha() {
        if (inPreviewMode || transitionAnimationPreviewMode || previewOpenAnimationInProgress) {
            return (oldFragment != null && oldFragment.inPreviewMode ? containerViewBack : containerView).getAlpha();
        } else {
            return 0f;
        }
    }

    @Override
    public void drawCurrentPreviewFragment(Canvas canvas, Drawable foregroundDrawable) {
        if (inPreviewMode || transitionAnimationPreviewMode || previewOpenAnimationInProgress) {
            final ViewGroup v = oldFragment != null && oldFragment.inPreviewMode ? containerViewBack : containerView;
            drawPreviewDrawables(canvas, v);
            if (v.getAlpha() < 1f) {
                canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), (int) (v.getAlpha() * 255), Canvas.ALL_SAVE_FLAG);
            } else {
                canvas.save();
            }
            canvas.concat(v.getMatrix());
            v.draw(canvas);
            if (foregroundDrawable != null) {
                final View child = v.getChildAt(0);
                if (child != null) {
                    final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
                    final Rect rect = new Rect();
                    child.getLocalVisibleRect(rect);
                    rect.offset(lp.leftMargin, lp.topMargin);
                    rect.top += Build.VERSION.SDK_INT >= 21 ? AndroidUtilities.statusBarHeight - 1 : 0;
                    foregroundDrawable.setAlpha((int) (v.getAlpha() * 255));
                    foregroundDrawable.setBounds(rect);
                    foregroundDrawable.draw(canvas);
                }
            }
            canvas.restore();
        }
    }

    private void drawPreviewDrawables(Canvas canvas, ViewGroup containerView) {
        View view = containerView.getChildAt(0);
        if (view != null) {
            previewBackgroundDrawable.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
            previewBackgroundDrawable.draw(canvas);
            if (previewMenu == null) {
                int width = dp(32), height = width / 2;
                int x = (getMeasuredWidth() - width) / 2;
                int y = (int) (view.getTop() + containerView.getTranslationY() - dp(12 + (Build.VERSION.SDK_INT < 21 ? 20 : 0)));
                Theme.moveUpDrawable.setBounds(x, y, x + width, y + height);
                Theme.moveUpDrawable.draw(canvas);
            }
        }
    }

    @Override
    public void setDelegate(INavigationLayoutDelegate INavigationLayoutDelegate) {
        delegate = INavigationLayoutDelegate;
    }

    private void onSlideAnimationEnd(final boolean backAnimation) {
        if (!backAnimation) {
            if (fragmentsStack.size() < 2) {
                return;
            }
            BaseFragment lastFragment = fragmentsStack.get(fragmentsStack.size() - 1);
            lastFragment.prepareFragmentToSlide(true, false);
            lastFragment.onPause();
            lastFragment.onFragmentDestroy();
            lastFragment.setParentLayout(null);

            fragmentsStack.remove(fragmentsStack.size() - 1);
            onFragmentStackChanged("onSlideAnimationEnd");

            LayoutContainer temp = containerView;
            containerView = containerViewBack;
            containerViewBack = temp;
            bringChildToFront(containerView);
            if (sheetContainer != null) {
                bringChildToFront(sheetContainer);
            }

            lastFragment = fragmentsStack.get(fragmentsStack.size() - 1);
            currentActionBar = lastFragment.actionBar;
            lastFragment.onResume();
            lastFragment.onBecomeFullyVisible();
            lastFragment.prepareFragmentToSlide(false, false);

            layoutToIgnore = containerView;
        } else {
            if (fragmentsStack.size() >= 2) {
                BaseFragment lastFragment = fragmentsStack.get(fragmentsStack.size() - 1);
                lastFragment.prepareFragmentToSlide(true, false);

                lastFragment = fragmentsStack.get(fragmentsStack.size() - 2);
                lastFragment.prepareFragmentToSlide(false, false);
                lastFragment.onPause();
                if (lastFragment.fragmentView != null) {
                    ViewGroup parent = (ViewGroup) lastFragment.fragmentView.getParent();
                    if (parent != null) {
                        lastFragment.onRemoveFromParent();
                        parent.removeViewInLayout(lastFragment.fragmentView);
                    }
                }
                if (lastFragment.actionBar != null && lastFragment.actionBar.shouldAddToContainer()) {
                    ViewGroup parent = (ViewGroup) lastFragment.actionBar.getParent();
                    if (parent != null) {
                        parent.removeViewInLayout(lastFragment.actionBar);
                    }
                }
                lastFragment.detachSheets();
            }
            layoutToIgnore = null;
        }
        containerViewBack.setVisibility(View.INVISIBLE);
        startedTracking = false;
        animationInProgress = false;
        containerView.setTranslationX(0);
        containerViewBack.setTranslationX(0);
        setInnerTranslationX(0);
        if (USE_ACTIONBAR_CROSSFADE) {
            invalidateActionBars();
        }
    }

    private void prepareForMoving(MotionEvent ev) {
        maybeStartTracking = false;
        startedTracking = true;
        layoutToIgnore = containerViewBack;
        startedTrackingX = (int) ev.getX();
        containerViewBack.setVisibility(View.VISIBLE);
        beginTrackingSent = false;

        BaseFragment lastFragment = fragmentsStack.get(fragmentsStack.size() - 2);
        View fragmentView = lastFragment.fragmentView;
        if (fragmentView == null) {
            fragmentView = lastFragment.createView(parentActivity);
            if (NekoConfig.disableVibration.Bool()) {
                VibrateUtil.disableHapticFeedback(fragmentView);
            }
        }
        ViewGroup parent = (ViewGroup) fragmentView.getParent();
        if (parent != null) {
            lastFragment.onRemoveFromParent();
            parent.removeView(fragmentView);
        }
        containerViewBack.addView(fragmentView);
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) fragmentView.getLayoutParams();
        layoutParams.width = LayoutHelper.MATCH_PARENT;
        layoutParams.height = LayoutHelper.MATCH_PARENT;
        layoutParams.topMargin = layoutParams.bottomMargin = layoutParams.rightMargin = layoutParams.leftMargin = 0;
        fragmentView.setLayoutParams(layoutParams);
        if (lastFragment.actionBar != null && lastFragment.actionBar.shouldAddToContainer()) {
            AndroidUtilities.removeFromParent(lastFragment.actionBar);
            if (removeActionBarExtraHeight) {
                lastFragment.actionBar.setOccupyStatusBar(false);
            }
            containerViewBack.addView(lastFragment.actionBar);
            lastFragment.actionBar.setTitleOverlayText(titleOverlayText, titleOverlayTextId, overlayAction);
        }
        lastFragment.attachSheets(containerViewBack);
        if (!lastFragment.hasOwnBackground && fragmentView.getBackground() == null) {
            fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }
        lastFragment.onResume();
        if (themeAnimatorSet != null) {
            presentingFragmentDescriptions = lastFragment.getThemeDescriptions();
        }

        BaseFragment currentFragment = fragmentsStack.get(fragmentsStack.size() - 1);
        currentFragment.prepareFragmentToSlide(true, true);
        lastFragment.prepareFragmentToSlide(false, true);

        if (USE_ACTIONBAR_CROSSFADE) {
            swipeProgress = 0f;
            invalidateActionBars();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!checkTransitionAnimation() && !inActionMode && !animationInProgress) {
            if (fragmentsStack.size() > 1 && allowSwipe()) {
                if (ev != null && ev.getAction() == MotionEvent.ACTION_DOWN) {
                    BaseFragment currentFragment = fragmentsStack.get(fragmentsStack.size() - 1);
                    if (!currentFragment.isSwipeBackEnabled(ev)) {
                        maybeStartTracking = false;
                        startedTracking = false;
                        return false;
                    }
                    startedTrackingPointerId = ev.getPointerId(0);
                    maybeStartTracking = true;
                    startedTrackingX = (int) ev.getX();
                    startedTrackingY = (int) ev.getY();
                    if (velocityTracker != null) {
                        velocityTracker.clear();
                    }
                } else if (ev != null && ev.getAction() == MotionEvent.ACTION_MOVE && ev.getPointerId(0) == startedTrackingPointerId) {
                    if (velocityTracker == null) {
                        velocityTracker = VelocityTracker.obtain();
                    }
                    int dx = Math.max(0, (int) (ev.getX() - startedTrackingX));
                    int dy = Math.abs((int) ev.getY() - startedTrackingY);
                    velocityTracker.addMovement(ev);
                    if (!transitionAnimationInProgress && !inPreviewMode && maybeStartTracking && !startedTracking && dx >= AndroidUtilities.getPixelsInCM(0.4f, true) && Math.abs(dx) / 3 > dy) {
                        BaseFragment currentFragment = fragmentsStack.get(fragmentsStack.size() - 1);
                        if (currentFragment.canBeginSlide() && findScrollingChild(this, ev.getX(), ev.getY()) == null) {
                            prepareForMoving(ev);
                        } else {
                            maybeStartTracking = false;
                        }
                    } else if (startedTracking) {
                        if (!beginTrackingSent) {
                            if (parentActivity.getCurrentFocus() != null) {
                                AndroidUtilities.hideKeyboard(parentActivity.getCurrentFocus());
                            }
                            BaseFragment currentFragment = fragmentsStack.get(fragmentsStack.size() - 1);
                            currentFragment.onBeginSlide();
                            beginTrackingSent = true;
                        }
                        containerView.setTranslationX(dx);
                        if (USE_SPRING_ANIMATION) {
                            containerViewBack.setTranslationX(-(containerView.getMeasuredWidth() - dx) * 0.35f);
                            if (USE_ACTIONBAR_CROSSFADE) {
                                swipeProgress = MathUtils.clamp((float) dx / containerView.getMeasuredWidth(), 0f, 1f);
                            }
                        }
                        setInnerTranslationX(dx);
                    }
                } else if (ev != null && ev.getPointerId(0) == startedTrackingPointerId && (ev.getAction() == MotionEvent.ACTION_CANCEL || ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_POINTER_UP)) {
                    if (velocityTracker == null) {
                        velocityTracker = VelocityTracker.obtain();
                    }
                    velocityTracker.computeCurrentVelocity(1000);
                    BaseFragment currentFragment = fragmentsStack.get(fragmentsStack.size() - 1);
                    if (!inPreviewMode && !transitionAnimationPreviewMode && !startedTracking && currentFragment.isSwipeBackEnabled(ev)) {
                        float velX = velocityTracker.getXVelocity();
                        float velY = velocityTracker.getYVelocity();
                        if (velX >= 3500 && velX > Math.abs(velY) && currentFragment.canBeginSlide()) {
                            prepareForMoving(ev);
                            if (!beginTrackingSent) {
                                if (((Activity) getContext()).getCurrentFocus() != null) {
                                    AndroidUtilities.hideKeyboard(((Activity) getContext()).getCurrentFocus());
                                }
                                beginTrackingSent = true;
                            }
                        }
                    }
                    if (startedTracking) {
                        float x = containerView.getX();
                        AnimatorSet animatorSet = new AnimatorSet();
                        float velX = velocityTracker.getXVelocity();
                        float velY = velocityTracker.getYVelocity();
                        final boolean backAnimation = x < containerView.getMeasuredWidth() / 3.0f && (velX < 3500 || velX < velY);
                        float distToMove;
                        boolean overrideTransition = currentFragment.shouldOverrideSlideTransition(false, backAnimation);

                        if (USE_SPRING_ANIMATION) {
                            FloatValueHolder valueHolder = new FloatValueHolder((x / containerView.getMeasuredWidth()) * SPRING_MULTIPLIER);
                            if (!backAnimation) {
                                currentSpringAnimation = new SpringAnimation(valueHolder)
                                        .setSpring(new SpringForce(SPRING_MULTIPLIER)
                                                .setStiffness(SPRING_STIFFNESS)
                                                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
                                if (velX != 0) {
                                    currentSpringAnimation.setStartVelocity(velX / 15f);
                                }
                            } else {
                                currentSpringAnimation = new SpringAnimation(valueHolder)
                                        .setSpring(new SpringForce(0f)
                                                .setStiffness(SPRING_STIFFNESS)
                                                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
                            }
                            currentSpringAnimation.addUpdateListener((animation, value, velocity) -> {
                                var progress = value / SPRING_MULTIPLIER;
                                containerView.setTranslationX(progress * containerView.getMeasuredWidth());
                                containerViewBack.setTranslationX(-(containerView.getMeasuredWidth() - progress * containerView.getMeasuredWidth()) * 0.35f);
                                setInnerTranslationX(progress * containerView.getMeasuredWidth());
                                if (USE_ACTIONBAR_CROSSFADE) {
                                    swipeProgress = progress;
                                }

                                if (!backAnimation) {
                                    getLastFragment().onTransitionAnimationProgress(false, progress);
                                    getBackgroundFragment().onTransitionAnimationProgress(true, progress);
                                } else {
                                    getBackgroundFragment().onTransitionAnimationProgress(true, 1f - progress);
                                }
                            });
                            currentSpringAnimation.addEndListener((animation, canceled, value, velocity) -> onSlideAnimationEnd(backAnimation));
                            currentSpringAnimation.start();

                            animationInProgress = true;
                            layoutToIgnore = containerViewBack;

                            if (velocityTracker != null) {
                                velocityTracker.recycle();
                                velocityTracker = null;
                            }
                            return startedTracking;
                        }

                        if (!backAnimation) {
                            distToMove = containerView.getMeasuredWidth() - x;
                            int duration = Math.max((int) (200.0f / containerView.getMeasuredWidth() * distToMove), 50);
                            if (!overrideTransition) {
                                animatorSet.playTogether(
                                        ObjectAnimator.ofFloat(containerView, View.TRANSLATION_X, containerView.getMeasuredWidth()).setDuration(duration),
                                        ObjectAnimator.ofFloat(this, "innerTranslationX", (float) containerView.getMeasuredWidth()).setDuration(duration)
                                );
                            }
                        } else {
                            distToMove = x;
                            int duration = Math.max((int) (320.0f / containerView.getMeasuredWidth() * distToMove), 120);
                            if (!overrideTransition) {
                                animatorSet.playTogether(
                                        ObjectAnimator.ofFloat(containerView, View.TRANSLATION_X, 0).setDuration(duration),
                                        ObjectAnimator.ofFloat(this, "innerTranslationX", 0.0f).setDuration(duration)
                                );
                                animatorSet.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
                            }
                        }

                        Animator customTransition = currentFragment.getCustomSlideTransition(false, backAnimation, distToMove);
                        if (customTransition != null) {
                            animatorSet.playTogether(customTransition);
                        }

                        BaseFragment lastFragment = fragmentsStack.get(fragmentsStack.size() - 2);
                        if (lastFragment != null) {
                            customTransition = lastFragment.getCustomSlideTransition(false, backAnimation, distToMove);
                            if (customTransition != null) {
                                animatorSet.playTogether(customTransition);
                            }
                        }

                        animatorSet.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animator) {
                                onSlideAnimationEnd(backAnimation);
                            }
                        });
                        animatorSet.start();
                        animationInProgress = true;
                        layoutToIgnore = containerViewBack;
                    } else {
                        maybeStartTracking = false;
                        startedTracking = false;
                        layoutToIgnore = null;
                    }
                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }
                } else if (ev == null) {
                    maybeStartTracking = false;
                    startedTracking = false;
                    layoutToIgnore = null;
                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }
                }
            }
            return startedTracking;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (transitionAnimationPreviewMode || startedTracking || checkTransitionAnimation() || fragmentsStack.isEmpty()) {
            return;
        }
        if (GroupCallPip.onBackPressed()) {
            return;
        }
        if (!storyViewerAttached() && currentActionBar != null && !currentActionBar.isActionModeShowed() && currentActionBar.isSearchFieldVisible) {
            currentActionBar.closeSearchField();
            return;
        }
        if (sheetFragment != null && !sheetFragment.onBackPressed()) {
            return;
        }
        BaseFragment lastFragment = fragmentsStack.get(fragmentsStack.size() - 1);
        if (lastFragment.onBackPressed()) {
            if (!fragmentsStack.isEmpty()) {
                closeLastFragment(true);
            }
        }
    }

    @Override
    public void onLowMemory() {
        for (BaseFragment fragment : fragmentsStack) {
            fragment.onLowMemory();
        }
    }

    private void onAnimationEndCheck(boolean byCheck) {
        onCloseAnimationEnd();
        onOpenAnimationEnd();
        if (waitingForKeyboardCloseRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(waitingForKeyboardCloseRunnable);
            waitingForKeyboardCloseRunnable = null;
        }
        if (currentAnimation != null) {
            if (byCheck) {
                currentAnimation.cancel();
            }
            currentAnimation = null;
        }
        if (currentSpringAnimation != null) {
            if (byCheck) {
                currentSpringAnimation.cancel();
            }
            currentSpringAnimation = null;
        }
        if (animationRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(animationRunnable);
            animationRunnable = null;
        }
        setAlpha(1.0f);
        containerView.setAlpha(1.0f);
        containerView.setScaleX(1.0f);
        containerView.setScaleY(1.0f);
        containerView.setTranslationX(0);
        containerView.setTranslationY(0);
        containerViewBack.setAlpha(1.0f);
        containerViewBack.setScaleX(1.0f);
        containerViewBack.setScaleY(1.0f);
        containerViewBack.setTranslationX(0);
        containerViewBack.setTranslationY(0);
        if (USE_ACTIONBAR_CROSSFADE) {
            invalidateActionBars();
        }
    }

    public BaseFragment getLastFragment() {
        if (fragmentsStack.isEmpty()) {
            return null;
        }
        return fragmentsStack.get(fragmentsStack.size() - 1);
    }

    @Override
    public boolean checkTransitionAnimation() {
        if (transitionAnimationPreviewMode) {
            return false;
        }
        if (transitionAnimationInProgress && (transitionAnimationStartTime < System.currentTimeMillis() - 1500 || inPreviewMode)) {
            onAnimationEndCheck(true);
        }
        return transitionAnimationInProgress;
    }

    @Override
    public boolean isPreviewOpenAnimationInProgress() {
        return previewOpenAnimationInProgress;
    }

    @Override
    public boolean isSwipeInProgress() {
        return startedTracking;
    }

    @Override
    public boolean isTransitionAnimationInProgress() {
        return transitionAnimationInProgress || animationInProgress;
    }

    private void presentFragmentInternalRemoveOld(boolean removeLast, final BaseFragment fragment) {
        if (fragment == null) {
            return;
        }
        fragment.onBecomeFullyHidden();
        fragment.onPause();
        if (removeLast) {
            fragment.onFragmentDestroy();
            fragment.setParentLayout(null);
            fragmentsStack.remove(fragment);
            onFragmentStackChanged("presentFragmentInternalRemoveOld");
        } else {
            if (fragment.fragmentView != null) {
                ViewGroup parent = (ViewGroup) fragment.fragmentView.getParent();
                if (parent != null) {
                    fragment.onRemoveFromParent();
                    try {
                        parent.removeViewInLayout(fragment.fragmentView);
                    } catch (Exception e) {
                        FileLog.e(e);
                        try {
                            parent.removeView(fragment.fragmentView);
                        } catch (Exception e2) {
                            FileLog.e(e2);
                        }
                    }
                }
            }
            if (fragment.actionBar != null && fragment.actionBar.shouldAddToContainer()) {
                ViewGroup parent = (ViewGroup) fragment.actionBar.getParent();
                if (parent != null) {
                    parent.removeViewInLayout(fragment.actionBar);
                }
            }
            fragment.detachSheets();
        }
        containerViewBack.setVisibility(View.INVISIBLE);
    }

    private void startLayoutAnimation(final boolean open, final boolean first, final boolean preview) {
        if (first) {
            animationProgress = 0.0f;
            lastFrameTime = System.nanoTime() / 1000000;
        }
        if (USE_SPRING_ANIMATION) {
            if (USE_ACTIONBAR_CROSSFADE) {
                swipeProgress = open ? 1f : 0f;
                invalidateActionBars();
            }
            FloatValueHolder valueHolder = new FloatValueHolder(0);
            currentSpringAnimation = new SpringAnimation(valueHolder)
                    .setSpring(new SpringForce(SPRING_MULTIPLIER)
                            .setStiffness(preview ? open ? SPRING_STIFFNESS_PREVIEW : SPRING_STIFFNESS_PREVIEW_OUT : SPRING_STIFFNESS)
                            .setDampingRatio(preview ? 0.6f : 1f));
            currentSpringAnimation.addUpdateListener((animation, value, velocity) -> {
                animationProgress = value / SPRING_MULTIPLIER;
                if (USE_ACTIONBAR_CROSSFADE) {
                    swipeProgress = MathUtils.clamp(open ? (1f - animationProgress) : animationProgress, 0f, 1f);
                }
                if (newFragment != null) {
                    newFragment.onTransitionAnimationProgress(true, animationProgress);
                }
                if (oldFragment != null) {
                    oldFragment.onTransitionAnimationProgress(false, animationProgress);
                }
                if (preview) {
                    Integer oldNavigationBarColor = oldFragment != null ? oldFragment.getNavigationBarColor() : null;
                    Integer newNavigationBarColor = newFragment != null ? newFragment.getNavigationBarColor() : null;
                    if (newFragment != null && oldNavigationBarColor != null) {
                        float ratio = MathUtils.clamp(4f * animationProgress, 0f, 1f);
                        newFragment.setNavigationBarColor(ColorUtils.blendARGB(oldNavigationBarColor, newNavigationBarColor, ratio));
                    }
                }
                float interpolated = animationProgress;
                float widthNoPaddings = getWidth() - getPaddingLeft() - getPaddingRight();
                if (open) {
                    float clampedInterpolated = MathUtils.clamp(interpolated, 0, 1);
                    if (preview) {
                        containerView.setTranslationX(0);
                        containerView.setTranslationY(0);

                        float scale = 0.5f + interpolated * 0.5f;
                        containerView.setScaleX(scale);
                        containerView.setScaleY(scale);
                        containerView.setAlpha(clampedInterpolated);

                        if (previewMenu != null) {
                            containerView.setTranslationY(AndroidUtilities.dp(40) * (1f - interpolated));
                            previewMenu.setTranslationY(-AndroidUtilities.dp(40 + 30) * (1f - interpolated));
                            previewMenu.setScaleX(0.95f + 0.05f * interpolated);
                            previewMenu.setScaleY(0.95f + 0.05f * interpolated);
                        }
                        previewBackgroundDrawable.setAlpha((int) (0x2e * clampedInterpolated));
                        Theme.moveUpDrawable.setAlpha((int) (255 * clampedInterpolated));
                        containerView.invalidate();
                        invalidate();
                    } else {
                        containerView.setTranslationX((1.0f - interpolated) * widthNoPaddings);
                        containerViewBack.setTranslationX(-interpolated * 0.35f * widthNoPaddings);
                        setInnerTranslationX((1.0f - interpolated) * widthNoPaddings);
                    }
                } else {
                    float clampedReverseInterpolated = MathUtils.clamp(1f - interpolated, 0, 1);
                    if (preview) {
                        containerViewBack.setTranslationX(0);
                        containerViewBack.setTranslationY(0);

                        float scale = 0.5f + (1f - interpolated) * 0.5f;
                        containerViewBack.setScaleX(scale);
                        containerViewBack.setScaleY(scale);
                        containerViewBack.setAlpha(clampedReverseInterpolated);
                        previewBackgroundDrawable.setAlpha((int) (0x2e * clampedReverseInterpolated));
                        if (previewMenu == null) {
                            Theme.moveUpDrawable.setAlpha((int) (255 * clampedReverseInterpolated));
                        }
                        containerView.invalidate();
                        invalidate();
                    } else {
                        containerViewBack.setTranslationX(interpolated * widthNoPaddings);
                        containerView.setTranslationX(-(1f - interpolated) * 0.35f * widthNoPaddings);
                        setInnerTranslationX(interpolated * widthNoPaddings);
                    }
                }
            });
            currentSpringAnimation.addEndListener((animation, canceled, value, velocity) -> {
                onAnimationEndCheck(false);
                setInnerTranslationX(0);
            });
            currentSpringAnimation.start();
            return;
        }
        AndroidUtilities.runOnUIThread(animationRunnable = new Runnable() {
            @Override
            public void run() {
                if (animationRunnable != this) {
                    return;
                }
                animationRunnable = null;
                if (first) {
                    transitionAnimationStartTime = System.currentTimeMillis();
                }
                long newTime = System.nanoTime() / 1000000;
                long dt = newTime - lastFrameTime;
                if (dt > 40 && first) {
                    dt = 0;
                } else if (dt > 18) {
                    dt = 18;
                }
                lastFrameTime = newTime;
                float duration = preview && open ? 190.0f : 150.0f;
                animationProgress += dt / duration;
                if (animationProgress > 1.0f) {
                    animationProgress = 1.0f;
                }
                if (newFragment != null) {
                    newFragment.onTransitionAnimationProgress(true, animationProgress);
                }
                if (oldFragment != null) {
                    oldFragment.onTransitionAnimationProgress(false, animationProgress);
                }
                Integer oldNavigationBarColor = oldFragment != null ? oldFragment.getNavigationBarColor() : null;
                Integer newNavigationBarColor = newFragment != null ? newFragment.getNavigationBarColor() : null;
                if (newFragment != null && oldNavigationBarColor != null) {
                    float ratio = MathUtils.clamp(4f * animationProgress, 0f, 1f);
                    int color = ColorUtils.blendARGB(oldNavigationBarColor, newNavigationBarColor, ratio);
                    if (sheetFragment != null) {
                        if (sheetFragment.sheetsStack != null) {
                            for (int i = 0; i < sheetFragment.sheetsStack.size(); ++i) {
                                BaseFragment.AttachedSheet sheet = sheetFragment.sheetsStack.get(i);
                                if (sheet.attachedToParent()) {
                                    color = sheet.getNavigationBarColor(color);
                                }
                            }
                        }
                    }
                    newFragment.setNavigationBarColor(color);
                }
                float interpolated;
                if (preview) {
                    if (open) {
                        interpolated = overshootInterpolator.getInterpolation(animationProgress);
                    } else {
                        interpolated = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(animationProgress);
                    }
                } else {
                    interpolated = decelerateInterpolator.getInterpolation(animationProgress);
                }
                if (open) {
                    float clampedInterpolated = MathUtils.clamp(interpolated, 0, 1);
                    containerView.setAlpha(clampedInterpolated);
                    if (preview) {
                        containerView.setScaleX(0.7f + 0.3f * interpolated);
                        containerView.setScaleY(0.7f + 0.3f * interpolated);
                        if (previewMenu != null) {
                            containerView.setTranslationY(dp(40) * (1f - interpolated));
                            previewMenu.setTranslationY(-dp(40 + 30) * (1f - interpolated));
                            previewMenu.setScaleX(0.95f + 0.05f * interpolated);
                            previewMenu.setScaleY(0.95f + 0.05f * interpolated);
                        }
                        previewBackgroundDrawable.setAlpha((int) (0x2e * clampedInterpolated));
                        Theme.moveUpDrawable.setAlpha((int) (255 * clampedInterpolated));
                        containerView.invalidate();
                        invalidate();
                    } else {
                        containerView.setTranslationX(dp(48) * (1.0f - interpolated));
                    }
                } else {
                    float clampedReverseInterpolated = MathUtils.clamp(1f - interpolated, 0, 1);
                    containerViewBack.setAlpha(clampedReverseInterpolated);
                    if (preview) {
                        containerViewBack.setScaleX(0.9f + 0.1f * (1.0f - interpolated));
                        containerViewBack.setScaleY(0.9f + 0.1f * (1.0f - interpolated));
                        previewBackgroundDrawable.setAlpha((int) (0x2e * clampedReverseInterpolated));
                        if (previewMenu == null) {
                            Theme.moveUpDrawable.setAlpha((int) (255 * clampedReverseInterpolated));
                        }
                        containerView.invalidate();
                        invalidate();
                    } else {
                        containerViewBack.setTranslationX(dp(48) * interpolated);
                    }
                }
                if (animationProgress < 1) {
                    startLayoutAnimation(open, false, preview);
                } else {
                    onAnimationEndCheck(false);
                }
            }
        });
    }

    @Override
    public void resumeDelayedFragmentAnimation() {
        delayedAnimationResumed = true;
        if (delayedOpenAnimationRunnable == null || waitingForKeyboardCloseRunnable != null) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(delayedOpenAnimationRunnable);
        delayedOpenAnimationRunnable.run();
        delayedOpenAnimationRunnable = null;
    }

    public boolean isInPreviewMode() {
        return inPreviewMode || transitionAnimationPreviewMode;
    }

    @Override
    public boolean isInPassivePreviewMode() {
        return (inPreviewMode && previewMenu == null) || transitionAnimationPreviewMode;
    }

    public boolean isInPreviewMenuMode() {
        return isInPreviewMode() && previewMenu != null;
    }

    @Override
    public boolean presentFragment(NavigationParams params) {
        BaseFragment fragment = params.fragment;
        boolean removeLast = params.removeLast;
        boolean forceWithoutAnimation = params.noAnimation;
        boolean check = params.checkPresentFromDelegate;
        boolean preview = params.preview;
        ActionBarPopupWindow.ActionBarPopupWindowLayout menu = params.menuView;

        if (fragment == null || checkTransitionAnimation() || delegate != null && check && !delegate.needPresentFragment(this, params) || !fragment.onFragmentCreate()) {
            return false;
        }
        BaseFragment lastFragment = getLastFragment();
        Dialog dialog = lastFragment != null ? lastFragment.getVisibleDialog() : null;
        if (dialog == null && LaunchActivity.instance != null && LaunchActivity.instance.getVisibleDialog() != null) {
            dialog = LaunchActivity.instance.getVisibleDialog();
        }
        if (lastFragment != null && shouldOpenFragmentOverlay(dialog)) {
            BaseFragment.BottomSheetParams bottomSheetParams = new BaseFragment.BottomSheetParams();
            bottomSheetParams.transitionFromLeft = true;
            bottomSheetParams.allowNestedScroll = false;
            lastFragment.showAsSheet(fragment, bottomSheetParams);
            return true;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("present fragment " + fragment.getClass().getSimpleName() + " args=" + fragment.getArguments());
        }
        StoryViewer.closeGlobalInstances();
        if (bottomSheetTabs != null && !bottomSheetTabs.doNotDismiss) {
            LaunchActivity.dismissAllWeb();
        }
        if (inPreviewMode && transitionAnimationPreviewMode) {
            if (delayedOpenAnimationRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(delayedOpenAnimationRunnable);
                delayedOpenAnimationRunnable = null;
            }
            closeLastFragment(false, true);
        }
        fragment.setInPreviewMode(preview);
        if (previewMenu != null) {
            if (previewMenu.getParent() != null) {
                ((ViewGroup) previewMenu.getParent()).removeView(previewMenu);
            }
            previewMenu = null;
        }
        previewMenu = menu;
        fragment.setInMenuMode(previewMenu != null);
        if (parentActivity.getCurrentFocus() != null && fragment.hideKeyboardOnShow() && !preview) {
            AndroidUtilities.hideKeyboard(parentActivity.getCurrentFocus());
        }
        boolean needAnimation = preview || !forceWithoutAnimation && MessagesController.getGlobalMainSettings().getBoolean("view_animations", true);

        final BaseFragment currentFragment = !fragmentsStack.isEmpty() ? fragmentsStack.get(fragmentsStack.size() - 1) : null;

        fragment.setParentLayout(this);
        View fragmentView = fragment.fragmentView;
        if (fragmentView == null) {
            fragmentView = fragment.createView(parentActivity);
            if (NekoConfig.disableVibration.Bool()) {
                VibrateUtil.disableHapticFeedback(fragmentView);
            }
        } else {
            ViewGroup parent = (ViewGroup) fragmentView.getParent();
            if (parent != null) {
                fragment.onRemoveFromParent();
                parent.removeView(fragmentView);
            }
        }

        View wrappedView = fragmentView;
        containerViewBack.addView(wrappedView);
        int menuHeight = 0;
        if (menu != null) {
            containerViewBack.addView(menu);
            menu.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.AT_MOST));
            menuHeight = menu.getMeasuredHeight() + dp(24);
            FrameLayout.LayoutParams menuParams = (FrameLayout.LayoutParams) menu.getLayoutParams();
            menuParams.width = LayoutHelper.WRAP_CONTENT;
            menuParams.height = LayoutHelper.WRAP_CONTENT;
            menuParams.topMargin = getMeasuredHeight() - menuHeight - dp(6);
            menu.setLayoutParams(menuParams);
        }
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) wrappedView.getLayoutParams();
        layoutParams.width = LayoutHelper.MATCH_PARENT;
        layoutParams.height = LayoutHelper.MATCH_PARENT;
        if (preview) {
            int height = fragment.getPreviewHeight();
            int statusBarHeight = (Build.VERSION.SDK_INT >= 21 ? AndroidUtilities.statusBarHeight : 0);
            if (height > 0 && height < getMeasuredHeight() - statusBarHeight) {
                layoutParams.height = height;
                layoutParams.topMargin = statusBarHeight + (getMeasuredHeight() - statusBarHeight - height) / 2;
            } else {
                layoutParams.topMargin = layoutParams.bottomMargin = dp(menu != null ? 0 : 24);
                layoutParams.topMargin += AndroidUtilities.statusBarHeight;
            }
            if (menu != null) {
                layoutParams.bottomMargin += menuHeight + dp(8);
//                layoutParams.topMargin += AndroidUtilities.dp(32);
            }
            layoutParams.rightMargin = layoutParams.leftMargin = dp(8);
        } else {
            layoutParams.topMargin = layoutParams.bottomMargin = layoutParams.rightMargin = layoutParams.leftMargin = 0;
        }
        wrappedView.setLayoutParams(layoutParams);
        if (fragment.actionBar != null && fragment.actionBar.shouldAddToContainer()) {
            if (removeActionBarExtraHeight) {
                fragment.actionBar.setOccupyStatusBar(false);
            }
            AndroidUtilities.removeFromParent(fragment.actionBar);
            containerViewBack.addView(fragment.actionBar);
            fragment.actionBar.setTitleOverlayText(titleOverlayText, titleOverlayTextId, overlayAction);
        }
        fragment.attachSheets(containerViewBack);
        fragmentsStack.add(fragment);

        onFragmentStackChanged("presentFragment");
        fragment.onResume();

        currentActionBar = fragment.actionBar;
        if (!fragment.hasOwnBackground && fragmentView.getBackground() == null) {
            fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }

        LayoutContainer temp = containerView;
        containerView = containerViewBack;
        containerViewBack = temp;
        containerView.setVisibility(View.VISIBLE);
        if (USE_ACTIONBAR_CROSSFADE) {
            swipeProgress = 1f;
        }
        setInnerTranslationX(0);
        containerView.setTranslationY(0);

        if (preview) {
            if (Build.VERSION.SDK_INT >= 21) {
                fragmentView.setOutlineProvider(new ViewOutlineProvider() {
                    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, AndroidUtilities.statusBarHeight, view.getMeasuredWidth(), view.getMeasuredHeight(), dp(6));
                    }
                });
                fragmentView.setClipToOutline(true);
                fragmentView.setElevation(dp(4));
            }
            if (previewBackgroundDrawable == null) {
                previewBackgroundDrawable = new ColorDrawable(0x2e000000);
            }
            previewBackgroundDrawable.setAlpha(0);
            Theme.moveUpDrawable.setAlpha(0);
        }

        bringChildToFront(containerView);
        if (sheetContainer != null) {
            bringChildToFront(sheetContainer);
        }
        if (!needAnimation) {
            presentFragmentInternalRemoveOld(removeLast, currentFragment);
            if (backgroundView != null) {
                backgroundView.setVisibility(VISIBLE);
            }
        }

        if (themeAnimatorSet != null) {
            presentingFragmentDescriptions = fragment.getThemeDescriptions();
        }

        if (needAnimation || preview) {
            if (useAlphaAnimations && fragmentsStack.size() == 1) {
                presentFragmentInternalRemoveOld(removeLast, currentFragment);

                transitionAnimationStartTime = System.currentTimeMillis();
                transitionAnimationInProgress = true;
                layoutToIgnore = containerView;
                onOpenAnimationEndRunnable = () -> {
                    if (currentFragment != null) {
                        currentFragment.onTransitionAnimationEnd(false, false);
                    }
                    fragment.onTransitionAnimationEnd(true, false);
                    fragment.onBecomeFullyVisible();
                };
                ArrayList<Animator> animators = new ArrayList<>();
                animators.add(ObjectAnimator.ofFloat(this, View.ALPHA, 0.0f, 1.0f));
                if (backgroundView != null) {
                    backgroundView.setVisibility(VISIBLE);
                    animators.add(ObjectAnimator.ofFloat(backgroundView, View.ALPHA, 0.0f, 1.0f));
                }
                if (currentFragment != null) {
                    currentFragment.onTransitionAnimationStart(false, false);
                }
                fragment.onTransitionAnimationStart(true, false);
                currentAnimation = new AnimatorSet();
                currentAnimation.playTogether(animators);
                currentAnimation.setInterpolator(accelerateDecelerateInterpolator);
                currentAnimation.setDuration(200);
                currentAnimation.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        onAnimationEndCheck(false);
                    }
                });
                currentAnimation.start();
            } else {
                transitionAnimationPreviewMode = preview;
                transitionAnimationStartTime = System.currentTimeMillis();
                transitionAnimationInProgress = true;
                layoutToIgnore = containerView;
                onOpenAnimationEndRunnable = () -> {
                    if (preview) {
                        inPreviewMode = true;
                        previewMenu = menu;
                        transitionAnimationPreviewMode = false;
                        containerView.setScaleX(1.0f);
                        containerView.setScaleY(1.0f);
                    } else {
                        presentFragmentInternalRemoveOld(removeLast, currentFragment);
                        containerView.setTranslationX(0);
                    }
                    if (currentFragment != null) {
                        currentFragment.onTransitionAnimationEnd(false, false);
                    }
                    fragment.onTransitionAnimationEnd(true, false);
                    fragment.onBecomeFullyVisible();
                };
                boolean noDelay;
                if (noDelay = !fragment.needDelayOpenAnimation()) {
                    if (currentFragment != null) {
                        currentFragment.onTransitionAnimationStart(false, false);
                    }
                    fragment.onTransitionAnimationStart(true, false);
                }

                delayedAnimationResumed = false;
                oldFragment = currentFragment;
                newFragment = fragment;
                AnimatorSet animation = null;
                if (!preview) {
                    animation = fragment.onCustomTransitionAnimation(true, () -> onAnimationEndCheck(false));
                }
                if (animation == null) {
                    if (USE_SPRING_ANIMATION) {
                        if (preview) {
                            containerView.setAlpha(0.0f);
                            containerView.setTranslationX(0.0f);
                            containerView.setScaleX(0.5f);
                            containerView.setScaleY(0.5f);
                        } else {
                            containerView.setTranslationX(getWidth() - getPaddingLeft() - getPaddingRight());
                        }
                    } else {
                        containerView.setAlpha(0.0f);
                        if (preview) {
                            containerView.setTranslationX(0.0f);
                            containerView.setScaleX(0.9f);
                            containerView.setScaleY(0.9f);
                        } else {
                            containerView.setTranslationX(48.0f);
                            containerView.setScaleX(1.0f);
                            containerView.setScaleY(1.0f);
                        }
                    }
                    if (containerView.isKeyboardVisible || containerViewBack.isKeyboardVisible) {
                        if (currentFragment != null && !preview) {
                            currentFragment.saveKeyboardPositionBeforeTransition();
                        }
                        waitingForKeyboardCloseRunnable = new Runnable() {
                            @Override
                            public void run() {
                                if (waitingForKeyboardCloseRunnable != this) {
                                    return;
                                }
                                waitingForKeyboardCloseRunnable = null;
                                if (noDelay) {
                                    if (currentFragment != null) {
                                        currentFragment.onTransitionAnimationStart(false, false);
                                    }
                                    fragment.onTransitionAnimationStart(true, false);
                                    startLayoutAnimation(true, true, preview);
                                } else if (delayedOpenAnimationRunnable != null) {
                                    AndroidUtilities.cancelRunOnUIThread(delayedOpenAnimationRunnable);
                                    if (delayedAnimationResumed) {
                                        delayedOpenAnimationRunnable.run();
                                    } else {
                                        AndroidUtilities.runOnUIThread(delayedOpenAnimationRunnable, 200);
                                    }
                                }
                            }
                        };
                        if (fragment.needDelayOpenAnimation()) {
                            delayedOpenAnimationRunnable = new Runnable() {
                                @Override
                                public void run() {
                                    if (delayedOpenAnimationRunnable != this) {
                                        return;
                                    }
                                    delayedOpenAnimationRunnable = null;
                                    if (currentFragment != null) {
                                        currentFragment.onTransitionAnimationStart(false, false);
                                    }
                                    fragment.onTransitionAnimationStart(true, false);
                                    startLayoutAnimation(true, true, preview);
                                }
                            };
                        }
                        AndroidUtilities.runOnUIThread(waitingForKeyboardCloseRunnable, 250);
                    } else if (fragment.needDelayOpenAnimation()) {
                        delayedOpenAnimationRunnable = new Runnable() {
                            @Override
                            public void run() {
                                if (delayedOpenAnimationRunnable != this) {
                                    return;
                                }
                                delayedOpenAnimationRunnable = null;
                                fragment.onTransitionAnimationStart(true, false);
                                startLayoutAnimation(true, true, preview);
                            }
                        };
                        AndroidUtilities.runOnUIThread(delayedOpenAnimationRunnable, 200);
                    } else {
                        startLayoutAnimation(true, true, preview);
                    }
                } else {
                    if (!preview && (containerView.isKeyboardVisible || containerViewBack.isKeyboardVisible) && currentFragment != null) {
                        currentFragment.saveKeyboardPositionBeforeTransition();
                    }
                    currentAnimation = animation;
                }
            }
        } else {
            if (backgroundView != null) {
                backgroundView.setAlpha(1.0f);
                backgroundView.setVisibility(VISIBLE);
            }
            if (currentFragment != null) {
                currentFragment.onTransitionAnimationStart(false, false);
                currentFragment.onTransitionAnimationEnd(false, false);
            }
            fragment.onTransitionAnimationStart(true, false);
            fragment.onTransitionAnimationEnd(true, false);
            fragment.onBecomeFullyVisible();
        }
        return true;
    }

    private boolean shouldOpenFragmentOverlay(Dialog visibleDialog) {
        return (visibleDialog != null && visibleDialog.isShowing()) && (visibleDialog instanceof ChatAttachAlert || visibleDialog instanceof BotWebViewSheet);
    }

    @Override
    public List<BaseFragment> getFragmentStack() {
        return fragmentsStack;
    }

    @Override
    public void setFragmentStackChangedListener(Runnable onFragmentStackChanged) {
        this.onFragmentStackChangedListener = onFragmentStackChanged;
    }

    private void onFragmentStackChanged(String action) {
        if (onFragmentStackChangedListener != null) {
            onFragmentStackChangedListener.run();
        }
        ImageLoader.getInstance().onFragmentStackChanged();
        checkBlackScreen(action);
    }

    @Override
    public boolean addFragmentToStack(BaseFragment fragment, int position) {
        if (delegate != null && !delegate.needAddFragmentToStack(fragment, this) || !fragment.onFragmentCreate()) {
            return false;
        }
        if (fragmentsStack.contains(fragment)) {
            return false;
        }
        fragment.setParentLayout(this);
        if (position == -1 || position == INavigationLayout.FORCE_NOT_ATTACH_VIEW) {
            if (!fragmentsStack.isEmpty()) {
                BaseFragment previousFragment = fragmentsStack.get(fragmentsStack.size() - 1);
                previousFragment.onPause();
                if (previousFragment.actionBar != null && previousFragment.actionBar.shouldAddToContainer()) {
                    ViewGroup parent = (ViewGroup) previousFragment.actionBar.getParent();
                    if (parent != null) {
                        parent.removeView(previousFragment.actionBar);
                    }
                }
                if (previousFragment.fragmentView != null) {
                    ViewGroup parent = (ViewGroup) previousFragment.fragmentView.getParent();
                    if (parent != null) {
                        previousFragment.onRemoveFromParent();
                        parent.removeView(previousFragment.fragmentView);
                    }
                }
                previousFragment.detachSheets();
            }
            fragmentsStack.add(fragment);
            if (position != INavigationLayout.FORCE_NOT_ATTACH_VIEW) {
                attachView(fragment);
                fragment.onResume();
                fragment.onTransitionAnimationEnd(false, true);
                fragment.onTransitionAnimationEnd(true, true);
                fragment.onBecomeFullyVisible();
            }
            onFragmentStackChanged("addFragmentToStack " + position);
        } else {
            if (position == INavigationLayout.FORCE_ATTACH_VIEW_AS_FIRST) {
                position = 0;
                attachViewTo(fragment, position);
            }
            fragmentsStack.add(position, fragment);
            onFragmentStackChanged("addFragmentToStack");
        }
        if (!useAlphaAnimations) {
            setVisibility(VISIBLE);
            if (backgroundView != null) {
                backgroundView.setVisibility(VISIBLE);
            }
        }
        return true;
    }

    private void attachView(BaseFragment fragment) {
        View fragmentView = fragment.fragmentView;
        if (fragmentView == null) {
            fragmentView = fragment.createView(parentActivity);
        } else {
            ViewGroup parent = (ViewGroup) fragmentView.getParent();
            if (parent != null) {
                fragment.onRemoveFromParent();
                parent.removeView(fragmentView);
            }
        }
        if (!fragment.hasOwnBackground && fragmentView.getBackground() == null) {
            fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }
        containerView.addView(fragmentView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        if (fragment.actionBar != null && fragment.actionBar.shouldAddToContainer()) {
            if (removeActionBarExtraHeight) {
                fragment.actionBar.setOccupyStatusBar(false);
            }
            ViewGroup parent = (ViewGroup) fragment.actionBar.getParent();
            if (parent != null) {
                parent.removeView(fragment.actionBar);
            }
            containerView.addView(fragment.actionBar);
            fragment.actionBar.setTitleOverlayText(titleOverlayText, titleOverlayTextId, overlayAction);
        }
        fragment.attachSheets(containerView);
    }

    private void attachViewTo(BaseFragment fragment, int position) {
        View fragmentView = fragment.fragmentView;
        if (fragmentView == null) {
            fragmentView = fragment.createView(parentActivity);
        } else {
            ViewGroup parent = (ViewGroup) fragmentView.getParent();
            if (parent != null) {
                fragment.onRemoveFromParent();
                parent.removeView(fragmentView);
            }
        }
        if (!fragment.hasOwnBackground && fragmentView.getBackground() == null) {
            fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }
        containerView.addView(fragmentView, Utilities.clamp(position, containerView.getChildCount(), 0), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        if (fragment.actionBar != null && fragment.actionBar.shouldAddToContainer()) {
            if (removeActionBarExtraHeight) {
                fragment.actionBar.setOccupyStatusBar(false);
            }
            ViewGroup parent = (ViewGroup) fragment.actionBar.getParent();
            if (parent != null) {
                parent.removeView(fragment.actionBar);
            }
            containerView.addView(fragment.actionBar);
            fragment.actionBar.setTitleOverlayText(titleOverlayText, titleOverlayTextId, overlayAction);
        }
        fragment.attachSheets(containerView);
    }

    private void closeLastFragmentInternalRemoveOld(BaseFragment fragment) {
        fragment.finishing = true;
        fragment.onPause();
        fragment.onFragmentDestroy();
        fragment.setParentLayout(null);
        fragmentsStack.remove(fragment);
        containerViewBack.setVisibility(View.INVISIBLE);
        containerViewBack.setTranslationY(0);
        bringChildToFront(containerView);
        if (sheetContainer != null) {
            bringChildToFront(sheetContainer);
        }
        onFragmentStackChanged("closeLastFragmentInternalRemoveOld");
    }

    @Override
    public void movePreviewFragment(float dy) {
        if (!inPreviewMode || previewMenu != null || transitionAnimationPreviewMode) {
            return;
        }
        float currentTranslation = containerView.getTranslationY();
        float nextTranslation = -dy;
        if (nextTranslation > 0) {
            nextTranslation = 0;
        } else if (nextTranslation < -dp(60)) {
            nextTranslation = 0;
            expandPreviewFragment();
        }
        if (currentTranslation != nextTranslation) {
            containerView.setTranslationY(nextTranslation);
            invalidate();
        }
    }

    @Override
    public void expandPreviewFragment() {
        previewOpenAnimationInProgress = true;
        inPreviewMode = false;

        BaseFragment prevFragment = fragmentsStack.get(fragmentsStack.size() - 2);
        BaseFragment fragment = fragmentsStack.get(fragmentsStack.size() - 1);

        if (Build.VERSION.SDK_INT >= 21) {
            fragment.fragmentView.setOutlineProvider(null);
            fragment.fragmentView.setClipToOutline(false);
        }
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) fragment.fragmentView.getLayoutParams();
        layoutParams.topMargin = layoutParams.bottomMargin = layoutParams.rightMargin = layoutParams.leftMargin = 0;
        layoutParams.height = LayoutHelper.MATCH_PARENT;
        fragment.fragmentView.setLayoutParams(layoutParams);

        if (USE_SPRING_ANIMATION) {
            var view = fragment.fragmentView;
            rect.set(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
            float fromMenuY;
            if (previewMenu != null) {
                fromMenuY = previewMenu.getTranslationY();
            } else {
                fromMenuY = 0;
            }

            FloatValueHolder valueHolder = new FloatValueHolder(0);
            currentSpringAnimation = new SpringAnimation(valueHolder)
                    .setSpring(new SpringForce(SPRING_MULTIPLIER)
                            .setStiffness(SPRING_STIFFNESS_PREVIEW_EXPAND)
                            .setDampingRatio(0.6f));
            currentSpringAnimation.addUpdateListener((animation, value, velocity) -> {
                var progress = value / SPRING_MULTIPLIER;

                view.setPivotX(rect.centerX());
                view.setPivotY(rect.centerY());
                view.setScaleX(AndroidUtilities.lerp(rect.width() / (float) view.getWidth(), 1f, progress));
                view.setScaleY(AndroidUtilities.lerp(rect.height() / (float) view.getHeight(), 1f, progress));

                if (previewMenu != null) {
                    previewMenu.setTranslationY(AndroidUtilities.lerp(fromMenuY, getHeight(), progress));
                }
            });
            currentSpringAnimation.addEndListener((animation, canceled, value, velocity) -> {
                presentFragmentInternalRemoveOld(false, prevFragment);
                previewOpenAnimationInProgress = false;
                fragment.onPreviewOpenAnimationEnd();
            });
            currentSpringAnimation.start();
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);

            fragment.setInPreviewMode(false);
            fragment.setInMenuMode(false);
            return;
        }

        presentFragmentInternalRemoveOld(false, prevFragment);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(
                ObjectAnimator.ofFloat(fragment.fragmentView, View.SCALE_X, 1.0f, 1.05f, 1.0f),
                ObjectAnimator.ofFloat(fragment.fragmentView, View.SCALE_Y, 1.0f, 1.05f, 1.0f));
        animatorSet.setDuration(200);
        animatorSet.setInterpolator(new CubicBezierInterpolator(0.42, 0.0, 0.58, 1.0));
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                previewOpenAnimationInProgress = false;
                fragment.onPreviewOpenAnimationEnd();
            }
        });
        animatorSet.start();
        if (!NekoConfig.disableVibration.Bool()) {
            try {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            } catch (Exception ignore) {}
        }

        fragment.setInPreviewMode(false);
        fragment.setInMenuMode(false);

        try {
            AndroidUtilities.setLightStatusBar(parentActivity.getWindow(), Theme.getColor(Theme.key_actionBarDefault) == Color.WHITE || (fragment.hasForceLightStatusBar() && !Theme.getCurrentTheme().isDark()), fragment.hasForceLightStatusBar());
        } catch (Exception ignore) {}
    }

    @Override
    public void finishPreviewFragment() {
        if (!inPreviewMode && !transitionAnimationPreviewMode) {
            return;
        }
        if (delayedOpenAnimationRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(delayedOpenAnimationRunnable);
            delayedOpenAnimationRunnable = null;
        }
        closeLastFragment(true);
    }

    public void closeLastFragment(boolean animated) {
        closeLastFragment(animated, false);
    }

    public void closeLastFragment(boolean animated, boolean forceNoAnimation) {
        BaseFragment fragment = getLastFragment();
        if (fragment != null && fragment.closeLastFragment()) {
            return;
        }
        if (delegate != null && !delegate.needCloseLastFragment(this) || checkTransitionAnimation() || fragmentsStack.isEmpty()) {
            return;
        }
        if (parentActivity.getCurrentFocus() != null) {
            AndroidUtilities.hideKeyboard(parentActivity.getCurrentFocus());
        }
        setInnerTranslationX(0);
        boolean needAnimation = !forceNoAnimation && (inPreviewMode || transitionAnimationPreviewMode || animated && MessagesController.getGlobalMainSettings().getBoolean("view_animations", true));
        final BaseFragment currentFragment = fragmentsStack.get(fragmentsStack.size() - 1);
        BaseFragment previousFragment = null;
        if (fragmentsStack.size() > 1) {
            previousFragment = fragmentsStack.get(fragmentsStack.size() - 2);
        }

        if (previousFragment != null) {
            AndroidUtilities.setLightStatusBar(parentActivity.getWindow(), ColorUtils.calculateLuminance(Theme.getColor(Theme.key_actionBarDefault)) > 0.7f || (previousFragment.hasForceLightStatusBar() && !Theme.getCurrentTheme().isDark()), previousFragment.hasForceLightStatusBar());
            LayoutContainer temp = containerView;
            containerView = containerViewBack;
            containerViewBack = temp;

            previousFragment.setParentLayout(this);
            View fragmentView = previousFragment.fragmentView;
            if (fragmentView == null) {
                fragmentView = previousFragment.createView(parentActivity);
                if (NekoConfig.disableVibration.Bool()) {
                    VibrateUtil.disableHapticFeedback(fragmentView);
                }
            }

            if (!inPreviewMode) {
                containerView.setVisibility(View.VISIBLE);
                ViewGroup parent = (ViewGroup) fragmentView.getParent();
                if (parent != null) {
                    previousFragment.onRemoveFromParent();
                    try {
                        parent.removeView(fragmentView);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
                containerView.addView(fragmentView);
                FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) fragmentView.getLayoutParams();
                layoutParams.width = LayoutHelper.MATCH_PARENT;
                layoutParams.height = LayoutHelper.MATCH_PARENT;
                layoutParams.topMargin = layoutParams.bottomMargin = layoutParams.rightMargin = layoutParams.leftMargin = 0;
                fragmentView.setLayoutParams(layoutParams);
                if (previousFragment.actionBar != null && previousFragment.actionBar.shouldAddToContainer()) {
                    if (removeActionBarExtraHeight) {
                        previousFragment.actionBar.setOccupyStatusBar(false);
                    }
                    AndroidUtilities.removeFromParent(previousFragment.actionBar);
                    containerView.addView(previousFragment.actionBar);
                    previousFragment.actionBar.setTitleOverlayText(titleOverlayText, titleOverlayTextId, overlayAction);
                }
                previousFragment.attachSheets(containerView);
            }

            newFragment = previousFragment;
            oldFragment = currentFragment;
            previousFragment.onTransitionAnimationStart(true, true);
            currentFragment.onTransitionAnimationStart(false, true);
            previousFragment.onResume();
            if (themeAnimatorSet != null) {
                presentingFragmentDescriptions = previousFragment.getThemeDescriptions();
            }
            currentActionBar = previousFragment.actionBar;
            if (!previousFragment.hasOwnBackground && fragmentView.getBackground() == null) {
                fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }

            if (needAnimation) {
                transitionAnimationStartTime = System.currentTimeMillis();
                transitionAnimationInProgress = true;
                layoutToIgnore = containerView;
                final BaseFragment previousFragmentFinal = previousFragment;
                currentFragment.setRemovingFromStack(true);
                onCloseAnimationEndRunnable = () -> {
                    if (previewMenu != null) {
                        ViewGroup parent = (ViewGroup) previewMenu.getParent();
                        if (parent != null) {
                            //ViewGroup grandparent = (ViewGroup) parent.getParent();
                            parent.removeView(previewMenu);
                            /*if (grandparent != null) {
                                grandparent.removeView(parent);
                            }*/
                        }
                    }
                    if (inPreviewMode || transitionAnimationPreviewMode) {
                        containerViewBack.setScaleX(1.0f);
                        containerViewBack.setScaleY(1.0f);
                        inPreviewMode = false;
                        previewMenu = null;
                        transitionAnimationPreviewMode = false;
                    } else {
                        containerViewBack.setTranslationX(0);
                    }
                    closeLastFragmentInternalRemoveOld(currentFragment);
                    currentFragment.setRemovingFromStack(false);
                    currentFragment.onTransitionAnimationEnd(false, true);
                    previousFragmentFinal.onTransitionAnimationEnd(true, true);
                    previousFragmentFinal.onBecomeFullyVisible();
                };
                AnimatorSet animation = null;
                if (!inPreviewMode && !transitionAnimationPreviewMode) {
                    animation = currentFragment.onCustomTransitionAnimation(false, () -> onAnimationEndCheck(false));
                }
                if (animation == null) {
                    if (!inPreviewMode && (containerView.isKeyboardVisible || containerViewBack.isKeyboardVisible)) {
                        waitingForKeyboardCloseRunnable = new Runnable() {
                            @Override
                            public void run() {
                                if (waitingForKeyboardCloseRunnable != this) {
                                    return;
                                }
                                waitingForKeyboardCloseRunnable = null;
                                startLayoutAnimation(false, true, false);
                            }
                        };
                        AndroidUtilities.runOnUIThread(waitingForKeyboardCloseRunnable, 200);
                    } else {
                        startLayoutAnimation(false, true, inPreviewMode || transitionAnimationPreviewMode);
                    }
                } else {
                    currentAnimation = animation;
                    if (Bulletin.getVisibleBulletin() != null && Bulletin.getVisibleBulletin().isShowing()) {
                        Bulletin.getVisibleBulletin().hide();
                    }
                }
                onFragmentStackChanged("closeLastFragment");
            } else {
                closeLastFragmentInternalRemoveOld(currentFragment);
                currentFragment.onTransitionAnimationEnd(false, true);
                previousFragment.onTransitionAnimationEnd(true, true);
                previousFragment.onBecomeFullyVisible();
            }
        } else {
            if (useAlphaAnimations && !forceNoAnimation) {
                transitionAnimationStartTime = System.currentTimeMillis();
                transitionAnimationInProgress = true;
                layoutToIgnore = containerView;

                onCloseAnimationEndRunnable = () -> {
                    removeFragmentFromStackInternal(currentFragment, false);
                    setVisibility(GONE);
                    if (backgroundView != null) {
                        backgroundView.setVisibility(GONE);
                    }
                    if (drawerLayoutContainer != null) {
                        drawerLayoutContainer.setAllowOpenDrawer(true, false);
                    }
                };

                ArrayList<Animator> animators = new ArrayList<>();
                animators.add(ObjectAnimator.ofFloat(this, View.ALPHA, 1.0f, 0.0f));
                if (backgroundView != null) {
                    animators.add(ObjectAnimator.ofFloat(backgroundView, View.ALPHA, 1.0f, 0.0f));
                }

                currentAnimation = new AnimatorSet();
                currentAnimation.playTogether(animators);
                currentAnimation.setInterpolator(accelerateDecelerateInterpolator);
                currentAnimation.setDuration(200);
                currentAnimation.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                        transitionAnimationStartTime = System.currentTimeMillis();
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        onAnimationEndCheck(false);
                    }
                });
                currentAnimation.start();
            } else {
                removeFragmentFromStackInternal(currentFragment, false);
                setVisibility(GONE);
                if (backgroundView != null) {
                    backgroundView.setVisibility(GONE);
                }
            }
        }
        currentFragment.onFragmentClosed();
    }

    @Override
    public void bringToFront(int i) {
        if (fragmentsStack.isEmpty() || !fragmentsStack.isEmpty() && fragmentsStack.size() - 1 == i && fragmentsStack.get(i).fragmentView != null) {
            return;
        }
        for (int a = 0; a < i; a++) {
            BaseFragment previousFragment = fragmentsStack.get(a);
            if (previousFragment.actionBar != null && previousFragment.actionBar.shouldAddToContainer()) {
                ViewGroup parent = (ViewGroup) previousFragment.actionBar.getParent();
                if (parent != null) {
                    parent.removeView(previousFragment.actionBar);
                }
            }
            if (previousFragment.fragmentView != null) {
                ViewGroup parent = (ViewGroup) previousFragment.fragmentView.getParent();
                if (parent != null) {
                    previousFragment.onPause();
                    previousFragment.onRemoveFromParent();
                    parent.removeView(previousFragment.fragmentView);
                }
            }
        }
        BaseFragment previousFragment = fragmentsStack.get(i);
        previousFragment.setParentLayout(this);
        View fragmentView = previousFragment.fragmentView;
        if (fragmentView == null) {
            fragmentView = previousFragment.createView(parentActivity);
            if (NekoConfig.disableVibration.Bool()) {
                VibrateUtil.disableHapticFeedback(fragmentView);
            }
        } else {
            ViewGroup parent = (ViewGroup) fragmentView.getParent();
            if (parent != null) {
                previousFragment.onRemoveFromParent();
                parent.removeView(fragmentView);
            }
        }
        containerView.addView(fragmentView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        if (previousFragment.actionBar != null && previousFragment.actionBar.shouldAddToContainer()) {
            if (removeActionBarExtraHeight) {
                previousFragment.actionBar.setOccupyStatusBar(false);
            }
            AndroidUtilities.removeFromParent(previousFragment.actionBar);
            containerView.addView(previousFragment.actionBar);
            previousFragment.actionBar.setTitleOverlayText(titleOverlayText, titleOverlayTextId, overlayAction);
        }
        previousFragment.attachSheets(containerView);
        previousFragment.onResume();
        currentActionBar = previousFragment.actionBar;
        if (!previousFragment.hasOwnBackground && fragmentView.getBackground() == null) {
            fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }
    }

    public void showLastFragment() {
        if (fragmentsStack.isEmpty()) {
            return;
        }
        bringToFront(fragmentsStack.size() - 1);
    }

    private void removeFragmentFromStackInternal(BaseFragment fragment, boolean allowFinishFragment) {
        if (!fragmentsStack.contains(fragment)) {
            return;
        }
        if (allowFinishFragment && fragmentsStack.get(fragmentsStack.size() - 1) == fragment) {
            fragment.finishFragment();
        } else {
            if (fragmentsStack.get(fragmentsStack.size() - 1) == fragment && fragmentsStack.size() > 1) {
                fragment.finishFragment(false);
            } else {
                fragment.onPause();
                fragment.onFragmentDestroy();
                fragment.setParentLayout(null);
                fragmentsStack.remove(fragment);
                onFragmentStackChanged("removeFragmentFromStackInternal " + allowFinishFragment);
            }
        }
    }

    @Override
    public void removeFragmentFromStack(BaseFragment fragment, boolean immediate) {
        if (((fragmentsStack.size() > 0 && fragmentsStack.get(fragmentsStack.size() - 1) == fragment) || (fragmentsStack.size() > 1 && fragmentsStack.get(fragmentsStack.size() - 2) == fragment))) {
            onOpenAnimationEnd();
            onCloseAnimationEnd();
        }
        checkBlackScreen("removeFragmentFromStack " + immediate);
        if (useAlphaAnimations && fragmentsStack.size() == 1 && AndroidUtilities.isTablet()) {
            closeLastFragment(true);
        } else {
            if (delegate != null && fragmentsStack.size() == 1 && AndroidUtilities.isTablet()) {
                delegate.needCloseLastFragment(this);
            }
            removeFragmentFromStackInternal(fragment, fragment.allowFinishFragmentInsteadOfRemoveFromStack() && !immediate);
        }
    }

    public void removeAllFragments() {
        for (int a = 0; a < fragmentsStack.size(); a++) {
            removeFragmentFromStackInternal(fragmentsStack.get(a), false);
            a--;
        }
        if (backgroundView != null) {
            backgroundView.animate().alpha(0.0f).setDuration(180).withEndAction(() -> {
                backgroundView.setVisibility(View.GONE);
            }).start();
        }
    }

    @Keep
    public void setThemeAnimationValue(float value) {
        themeAnimationValue = value;
        for (int j = 0, N = themeAnimatorDescriptions.size(); j < N; j++) {
            ArrayList<ThemeDescription> descriptions = themeAnimatorDescriptions.get(j);
            int[] startColors = animateStartColors.get(j);
            int[] endColors = animateEndColors.get(j);
            int rE, gE, bE, aE, rS, gS, bS, aS, a, r, g, b;
            for (int i = 0, N2 = descriptions.size(); i < N2; i++) {
                rE = Color.red(endColors[i]);
                gE = Color.green(endColors[i]);
                bE = Color.blue(endColors[i]);
                aE = Color.alpha(endColors[i]);

                rS = Color.red(startColors[i]);
                gS = Color.green(startColors[i]);
                bS = Color.blue(startColors[i]);
                aS = Color.alpha(startColors[i]);

                a = Math.min(255, (int) (aS + (aE - aS) * value));
                r = Math.min(255, (int) (rS + (rE - rS) * value));
                g = Math.min(255, (int) (gS + (gE - gS) * value));
                b = Math.min(255, (int) (bS + (bE - bS) * value));
                int color = Color.argb(a, r, g, b);
                ThemeDescription description = descriptions.get(i);
                description.setAnimatedColor(color);
                description.setColor(color, false, false);
            }
        }
        for (int j = 0, N = themeAnimatorDelegate.size(); j < N; j++) {
            ThemeDescription.ThemeDescriptionDelegate delegate = themeAnimatorDelegate.get(j);
            if (delegate != null) {
                delegate.didSetColor();
                delegate.onAnimationProgress(value);
            }
        }
        if (presentingFragmentDescriptions != null) {
            for (int i = 0, N = presentingFragmentDescriptions.size(); i < N; i++) {
                ThemeDescription description = presentingFragmentDescriptions.get(i);
                int key = description.getCurrentKey();
                description.setColor(Theme.getColor(key, description.resourcesProvider), false, false);
            }
        }
        if (animationProgressListener != null) {
            animationProgressListener.setProgress(value);
        }
        if (delegate != null) {
            delegate.onThemeProgress(value);
        }
    }

    @Keep
    @Override
    public float getThemeAnimationValue() {
        return themeAnimationValue;
    }

    private void addStartDescriptions(ArrayList<ThemeDescription> descriptions) {
        if (descriptions == null) {
            return;
        }
        themeAnimatorDescriptions.add(descriptions);
        int[] startColors = new int[descriptions.size()];
        animateStartColors.add(startColors);
        for (int a = 0, N = descriptions.size(); a < N; a++) {
            ThemeDescription description = descriptions.get(a);
            startColors[a] = description.getSetColor();
            ThemeDescription.ThemeDescriptionDelegate delegate = description.setDelegateDisabled();
            if (delegate != null && !themeAnimatorDelegate.contains(delegate)) {
                themeAnimatorDelegate.add(delegate);
            }
        }
    }

    private void addEndDescriptions(ArrayList<ThemeDescription> descriptions) {
        if (descriptions == null) {
            return;
        }
        int[] endColors = new int[descriptions.size()];
        animateEndColors.add(endColors);
        for (int a = 0, N = descriptions.size(); a < N; a++) {
            endColors[a] = descriptions.get(a).getSetColor();
        }
    }

    @Override
    public void animateThemedValues(ThemeAnimationSettings settings, Runnable onDone) {
        if (transitionAnimationInProgress || startedTracking) {
            animateThemeAfterAnimation = true;
            animateSetThemeAfterAnimation = settings.theme;
            animateSetThemeNightAfterAnimation = settings.nightTheme;
            animateSetThemeAccentIdAfterAnimation = settings.accentId;
            animateSetThemeAfterAnimationApply = settings.applyTrulyTheme;
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        if (themeAnimatorSet != null) {
            themeAnimatorSet.cancel();
            themeAnimatorSet = null;
        }
        final int fragmentCount = settings.onlyTopFragment ? 1 : fragmentsStack.size();
        Runnable next = () -> {
            boolean startAnimation = false;
            for (int i = 0; i < fragmentCount; i++) {
                BaseFragment fragment;
                if (i == 0) {
                    fragment = getLastFragment();
                } else {
                    if (!inPreviewMode && !transitionAnimationPreviewMode || fragmentsStack.size() <= 1) {
                        continue;
                    }
                    fragment = fragmentsStack.get(fragmentsStack.size() - 2);
                }
                if (fragment != null) {
                    startAnimation = true;
                    if (settings.resourcesProvider != null) {
                        if (messageDrawableOutStart == null) {
                            messageDrawableOutStart = new Theme.MessageDrawable(Theme.MessageDrawable.TYPE_TEXT, true, false, startColorsProvider);
                            messageDrawableOutStart.isCrossfadeBackground = true;
                            messageDrawableOutMediaStart = new Theme.MessageDrawable(Theme.MessageDrawable.TYPE_MEDIA, true, false, startColorsProvider);
                            messageDrawableOutMediaStart.isCrossfadeBackground = true;
                        }
                        startColorsProvider.saveColors(settings.resourcesProvider);
                    }
                    ArrayList<ThemeDescription> descriptions = fragment.getThemeDescriptions();
                    addStartDescriptions(descriptions);
                    if (fragment.visibleDialog instanceof BottomSheet) {
                        BottomSheet sheet = (BottomSheet) fragment.visibleDialog;
                        addStartDescriptions(sheet.getThemeDescriptions());
                    } else if (fragment.visibleDialog instanceof AlertDialog) {
                        AlertDialog dialog = (AlertDialog) fragment.visibleDialog;
                        addStartDescriptions(dialog.getThemeDescriptions());
                    }
                    if (i == 0) {
                        if (settings.afterStartDescriptionsAddedRunnable != null) {
                            settings.afterStartDescriptionsAddedRunnable.run();
                        }
                    }
                    addEndDescriptions(descriptions);
                    if (fragment.visibleDialog instanceof BottomSheet) {
                        addEndDescriptions(((BottomSheet) fragment.visibleDialog).getThemeDescriptions());
                    } else if (fragment.visibleDialog instanceof AlertDialog) {
                        addEndDescriptions(((AlertDialog) fragment.visibleDialog).getThemeDescriptions());
                    }
                }
            }
            if (startAnimation) {
                if (!settings.onlyTopFragment) {
                    int count = fragmentsStack.size() - (inPreviewMode || transitionAnimationPreviewMode ? 2 : 1);
                    for (int a = 0; a < count; a++) {
                        BaseFragment fragment = fragmentsStack.get(a);
                        fragment.clearViews();
                        fragment.setParentLayout(this);
                    }
                }
                if (settings.instant) {
                    setThemeAnimationValue(1.0f);
                    themeAnimatorDescriptions.clear();
                    animateStartColors.clear();
                    animateEndColors.clear();
                    themeAnimatorDelegate.clear();
                    presentingFragmentDescriptions = null;
                    animationProgressListener = null;
                    if (settings.afterAnimationRunnable != null) {
                        settings.afterAnimationRunnable.run();
                    }
                    if (onDone != null) {
                        onDone.run();
                    }
                    return;
                }
                Theme.setAnimatingColor(true);
                setThemeAnimationValue(0f);
                if (settings.beforeAnimationRunnable != null) {
                    settings.beforeAnimationRunnable.run();
                }
                animationProgressListener = settings.animationProgress;
                if (animationProgressListener != null) {
                    animationProgressListener.setProgress(0);
                }
                notificationsLocker.lock();
                themeAnimatorSet = new AnimatorSet();
                themeAnimatorSet.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        notificationsLocker.unlock();
                        if (animation.equals(themeAnimatorSet)) {
                            themeAnimatorDescriptions.clear();
                            animateStartColors.clear();
                            animateEndColors.clear();
                            themeAnimatorDelegate.clear();
                            Theme.setAnimatingColor(false);
                            presentingFragmentDescriptions = null;
                            animationProgressListener = null;
                            themeAnimatorSet = null;
                            if (settings.afterAnimationRunnable != null) {
                                settings.afterAnimationRunnable.run();
                            }
                        }
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        if (animation.equals(themeAnimatorSet)) {
                            themeAnimatorDescriptions.clear();
                            animateStartColors.clear();
                            animateEndColors.clear();
                            themeAnimatorDelegate.clear();
                            Theme.setAnimatingColor(false);
                            presentingFragmentDescriptions = null;
                            animationProgressListener = null;
                            themeAnimatorSet = null;
                            if (settings.afterAnimationRunnable != null) {
                                settings.afterAnimationRunnable.run();
                            }
                        }
                    }
                });
                themeAnimatorSet.playTogether(ObjectAnimator.ofFloat(this, "themeAnimationValue", 0.0f, 1.0f));
                themeAnimatorSet.setDuration(settings.duration);
                themeAnimatorSet.start();
            }
            if (onDone != null) {
                onDone.run();
            }
        };
        if (fragmentCount >= 1 && settings.applyTheme && settings.applyTrulyTheme) {
            if (settings.accentId != -1 && settings.theme != null) {
                settings.theme.setCurrentAccentId(settings.accentId);
                Theme.saveThemeAccents(settings.theme, true, false, true, false);
            }
            if (onDone == null) {
                Theme.applyTheme(settings.theme, settings.nightTheme);
                next.run();
            } else {
                Theme.applyThemeInBackground(settings.theme, settings.nightTheme, () -> AndroidUtilities.runOnUIThread(next));
            }
        } else {
            next.run();
        }
    }

    @Override
    public void rebuildLogout() {
        containerView.removeAllViews();
        containerViewBack.removeAllViews();
        currentActionBar = null;
        newFragment = null;
        oldFragment = null;
    }

    @Override
    public void rebuildAllFragmentViews(boolean last, boolean showLastAfter) {
        if (transitionAnimationInProgress || startedTracking) {
            rebuildAfterAnimation = true;
            rebuildLastAfterAnimation = last;
            showLastAfterAnimation = showLastAfter;
            return;
        }
        int size = fragmentsStack.size();
        if (!last) {
            size--;
        }
        if (inPreviewMode) {
            size--;
        }
        for (int a = 0; a < size; a++) {
            fragmentsStack.get(a).clearViews();
            fragmentsStack.get(a).setParentLayout(this);
        }
        if (delegate != null) {
            delegate.onRebuildAllFragments(this, last);
        }
        if (showLastAfter) {
            showLastFragment();
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && !checkTransitionAnimation() && !startedTracking && currentActionBar != null) {
            currentActionBar.onMenuButtonPressed();
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onActionModeStarted(Object mode) {
        if (currentActionBar != null) {
            currentActionBar.setVisibility(GONE);
        }
        inActionMode = true;
    }

    @Override
    public void onActionModeFinished(Object mode) {
        if (currentActionBar != null) {
            currentActionBar.setVisibility(VISIBLE);
        }
        inActionMode = false;
    }

    private void onCloseAnimationEnd() {
        if (transitionAnimationInProgress && onCloseAnimationEndRunnable != null) {
            if (currentAnimation != null) {
                AnimatorSet animatorSet = currentAnimation;
                currentAnimation = null;
                animatorSet.cancel();
            }
            transitionAnimationInProgress = false;
            layoutToIgnore = null;
            transitionAnimationPreviewMode = false;
            transitionAnimationStartTime = 0;
            newFragment = null;
            oldFragment = null;
            Runnable endRunnable = onCloseAnimationEndRunnable;
            onCloseAnimationEndRunnable = null;
            if (endRunnable != null) {
                endRunnable.run();
            }
            checkNeedRebuild();
            checkNeedRebuild();
        }
    }

    private void checkNeedRebuild() {
        if (rebuildAfterAnimation) {
            rebuildAllFragmentViews(rebuildLastAfterAnimation, showLastAfterAnimation);
            rebuildAfterAnimation = false;
        } else if (animateThemeAfterAnimation) {
            ThemeAnimationSettings settings = new ThemeAnimationSettings(animateSetThemeAfterAnimation, animateSetThemeAccentIdAfterAnimation, animateSetThemeNightAfterAnimation, false);
            if (!animateSetThemeAfterAnimationApply) {
                settings.applyTheme = settings.applyTrulyTheme = animateSetThemeAfterAnimationApply;
            }
            animateThemedValues(settings, null);
            animateSetThemeAfterAnimation = null;
            animateThemeAfterAnimation = false;
        }
    }

    private void onOpenAnimationEnd() {
        if (transitionAnimationInProgress && onOpenAnimationEndRunnable != null) {
            transitionAnimationInProgress = false;
            layoutToIgnore = null;
            transitionAnimationPreviewMode = false;
            transitionAnimationStartTime = 0;
            newFragment = null;
            oldFragment = null;
            Runnable endRunnable = onOpenAnimationEndRunnable;
            onOpenAnimationEndRunnable = null;
            endRunnable.run();
            checkNeedRebuild();
        }
    }

    @Override
    public void startActivityForResult(final Intent intent, final int requestCode) {
        if (parentActivity == null) {
            return;
        }
        if (transitionAnimationInProgress) {
            if (currentAnimation != null) {
                currentAnimation.cancel();
                currentAnimation = null;
            }
            if (currentSpringAnimation != null) {
                currentSpringAnimation.cancel();
                currentSpringAnimation = null;
            }
            if (onCloseAnimationEndRunnable != null) {
                onCloseAnimationEnd();
            } else if (onOpenAnimationEndRunnable != null) {
                onOpenAnimationEnd();
            }
            containerView.invalidate();
        }
        if (intent != null) {
            parentActivity.startActivityForResult(intent, requestCode);
        }
    }

    @Override
    public Theme.MessageDrawable getMessageDrawableOutStart() {
        return messageDrawableOutStart;
    }

    @Override
    public Theme.MessageDrawable getMessageDrawableOutMediaStart() {
        return messageDrawableOutMediaStart;
    }

    @Override
    public List<BackButtonMenu.PulledDialog> getPulledDialogs() {
        return pulledDialogs;
    }

    @Override
    public void setPulledDialogs(List<BackButtonMenu.PulledDialog> pulledDialogs) {
        this.pulledDialogs = pulledDialogs;
    }

    @Override
    public void setUseAlphaAnimations(boolean value) {
        useAlphaAnimations = value;
    }

    @Override
    public void setBackgroundView(View view) {
        backgroundView = view;
    }

    @Override
    public void setDrawerLayoutContainer(DrawerLayoutContainer layout) {
        drawerLayoutContainer = layout;
    }

    public DrawerLayoutContainer getDrawerLayoutContainer() {
        return drawerLayoutContainer;
    }

    public void setRemoveActionBarExtraHeight(boolean value) {
        removeActionBarExtraHeight = value;
    }

    @Override
    public void setTitleOverlayText(String title, int titleId, Runnable action) {
        titleOverlayText = title;
        titleOverlayTextId = titleId;
        overlayAction = action;
        for (int a = 0; a < fragmentsStack.size(); a++) {
            BaseFragment fragment = fragmentsStack.get(a);
            if (fragment.actionBar != null) {
                fragment.actionBar.setTitleOverlayText(titleOverlayText, titleOverlayTextId, action);
            }
        }
    }

    @Override
    public boolean extendActionMode(Menu menu) {
        return !fragmentsStack.isEmpty() && fragmentsStack.get(fragmentsStack.size() - 1).extendActionMode(menu);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void setFragmentPanTranslationOffset(int offset) {
        if (containerView != null) {
            containerView.setFragmentPanTranslationOffset(offset);
        }
    }

    @Override
    public FrameLayout getOverlayContainerView() {
        return this;
    }

    @Override
    public List<FloatingDebugController.DebugItem> onGetDebugItems() {
        BaseFragment fragment = getLastFragment();
        if (fragment != null) {
            List<FloatingDebugController.DebugItem> items = new ArrayList<>();
            if (fragment instanceof FloatingDebugProvider) {
                items.addAll(((FloatingDebugProvider) fragment).onGetDebugItems());
            }
            observeDebugItemsFromView(items, fragment.getFragmentView());
            return items;
        }
        return Collections.emptyList();
    }

    private void observeDebugItemsFromView(List<FloatingDebugController.DebugItem> items, View v) {
        if (v instanceof FloatingDebugProvider) {
            items.addAll(((FloatingDebugProvider) v).onGetDebugItems());
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                observeDebugItemsFromView(items, vg.getChildAt(i));
            }
        }
    }

    public static View findScrollingChild(ViewGroup parent, float x, float y) {
        int n = parent.getChildCount();
        for (int i = 0; i < n; i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            child.getHitRect(AndroidUtilities.rectTmp2);
            if (AndroidUtilities.rectTmp2.contains((int) x, (int) y)) {
                if (child.canScrollHorizontally(-1)) {
                    return child;
                } else if (child instanceof ViewGroup) {
                    View v = findScrollingChild((ViewGroup) child, x - AndroidUtilities.rectTmp2.left, y - AndroidUtilities.rectTmp2.top);
                    if (v != null) {
                        return v;
                    }
                }
            }
        }
        return null;
    }


    ArrayList<String> lastActions = new ArrayList<>();
    Runnable debugBlackScreenRunnable = () -> {
        if (attached && getLastFragment() != null && containerView.getChildCount() == 0) {
            if (BuildVars.DEBUG_VERSION) {
                FileLog.e(new RuntimeException(TextUtils.join(", ", lastActions)));
            }
            rebuildAllFragmentViews(true, true);
        }
    };

    public void checkBlackScreen(String action) {
//        if (!BuildVars.DEBUG_VERSION) {
//            return;
//        }
        if (BuildVars.DEBUG_VERSION) {
            lastActions.add(0, action + " " + fragmentsStack.size());
            if (lastActions.size() > 20) {
                ArrayList<String> actions = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    actions.add(lastActions.get(i));
                }
                lastActions = actions;
            }
        }
        AndroidUtilities.cancelRunOnUIThread(debugBlackScreenRunnable);
        AndroidUtilities.runOnUIThread(debugBlackScreenRunnable, 500);
    }
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
    }

    public int measureKeyboardHeight() {
        View rootView = getRootView();
        getWindowVisibleDisplayFrame(rect);
        if (rect.bottom == 0 && rect.top == 0) {
            return 0;
        }
        int usableViewHeight = rootView.getHeight() - (rect.top != 0 ? AndroidUtilities.statusBarHeight : 0) - AndroidUtilities.getViewInset(rootView);
        return Math.max(0, usableViewHeight - (rect.bottom - rect.top));
    }

    private boolean tabsEvents;
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final boolean tabs = ev.getY() > getHeight() - getBottomTabsHeight(true);

        BaseFragment.AttachedSheet lastSheet = null;
        if (lastSheet == null && sheetFragment != null && sheetFragment.getLastSheet() != null) {
            lastSheet = sheetFragment.getLastSheet();
            if (!lastSheet.attachedToParent() || lastSheet.getWindowView() == null) lastSheet = null;
        }
        if (lastSheet == null && getLastFragment() != null && getLastFragment().getLastSheet() != null) {
            lastSheet = getLastFragment().getLastSheet();
            if (!lastSheet.attachedToParent() || lastSheet.getWindowView() == null) lastSheet = null;
        }
        if (lastSheet != null) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                tabsEvents = tabs;
            }
            if (!tabsEvents) {
                if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                    tabsEvents = false;
                }
                return lastSheet.getWindowView().dispatchTouchEvent(ev);
            }
        }
        if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
            tabsEvents = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void setWindow(Window window) {
        this.window = window;
    }

    @Override
    public Window getWindow() {
        if (window != null) {
            return window;
        }
        if (getParentActivity() != null) {
            return getParentActivity().getWindow();
        }
        return null;
    }

    @Override
    public BottomSheetTabs getBottomSheetTabs() {
        return bottomSheetTabs;
    }

    @Override
    public void setNavigationBarColor(int color) {
        if (bottomSheetTabs != null) {
            bottomSheetTabs.setNavigationBarColor(color, !(startedTracking || animationInProgress));
        }
    }

    public void relayout() {
        requestLayout();
        containerView.requestLayout();
        containerViewBack.requestLayout();
        sheetContainer.requestLayout();
    }

    @Override
    public int getBottomTabsHeight(boolean animated) {
        if (main && bottomSheetTabs != null)
            return bottomSheetTabs.getHeight(animated);
        return 0;
    }

    // --- Spring Animation ---
    private static final boolean USE_SPRING_ANIMATION = NaConfig.INSTANCE.getSpringAnimation().Bool();
    private static final boolean USE_ACTIONBAR_CROSSFADE = USE_SPRING_ANIMATION && NaConfig.INSTANCE.getSpringAnimationCrossfade().Bool();
    private static final float SPRING_STIFFNESS = 700f;
    private static final float SPRING_STIFFNESS_PREVIEW = 650f;
    private static final float SPRING_STIFFNESS_PREVIEW_OUT = 800f;
    private static final float SPRING_STIFFNESS_PREVIEW_EXPAND = 750f;
    private static final float SPRING_MULTIPLIER = 1000f;
    private float swipeProgress;
    private SpringAnimation currentSpringAnimation;
    private MenuDrawable menuDrawable;

    private void invalidateActionBars() {
        if (getLastFragment() != null && getLastFragment().getActionBar() != null) {
            getLastFragment().getActionBar().invalidate();
        }
        if (getBackgroundFragment() != null && getBackgroundFragment().getActionBar() != null) {
            getBackgroundFragment().getActionBar().invalidate();
        }
    }

    public boolean isActionBarInCrossfade() {
        if (!USE_ACTIONBAR_CROSSFADE) {
            return false;
        }
        boolean crossfadeNoFragments = SharedConfig.animationsEnabled() && !isInPreviewMode() && (isSwipeInProgress() || isTransitionAnimationInProgress()) && currentAnimation == null;
        return crossfadeNoFragments && getLastFragment() != null && getLastFragment().isActionBarCrossfadeEnabled() && getBackgroundFragment() != null && getBackgroundFragment().isActionBarCrossfadeEnabled();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        super.draw(canvas);

        if (isActionBarInCrossfade()) {
            if (containerView == null || containerViewBack == null) {
                return;
            }

            BaseFragment foregroundFragment = getLastFragment();
            BaseFragment backgroundFragment = getBackgroundFragment();

            if (foregroundFragment == null || backgroundFragment == null) {
                return;
            }

            ActionBar fgActionBar = foregroundFragment.getActionBar();
            ActionBar bgActionBar = backgroundFragment.getActionBar();

            if (fgActionBar == null || bgActionBar == null) {
                return;
            }

            boolean useBackDrawable = false;
            boolean backDrawableReverse = false;
            Float backDrawableForcedProgress = null;

            if (!AndroidUtilities.isTablet()) {
                if (backgroundFragment.getBackButtonState() == BackButtonState.MENU && foregroundFragment.getBackButtonState() == BackButtonState.BACK) {
                    useBackDrawable = true;
                } else if (backgroundFragment.getBackButtonState() == BackButtonState.BACK && foregroundFragment.getBackButtonState() == BackButtonState.MENU) {
                    useBackDrawable = true;
                    backDrawableReverse = true;
                } else if (backgroundFragment.getBackButtonState() == BackButtonState.BACK && foregroundFragment.getBackButtonState() == BackButtonState.BACK) {
                    useBackDrawable = true;
                    backDrawableForcedProgress = 0f;
                } else if (backgroundFragment.getBackButtonState() == BackButtonState.MENU && foregroundFragment.getBackButtonState() == BackButtonState.MENU) {
                    useBackDrawable = true;
                    backDrawableForcedProgress = 1f;
                }
            }

            AndroidUtilities.rectTmp.set(getPaddingLeft(), getPaddingTop(), getWidth() - getPaddingLeft(), bgActionBar.getY() + getPaddingTop() + bgActionBar.getHeight());
            canvas.saveLayerAlpha(AndroidUtilities.rectTmp, (int) (swipeProgress * 0xFF), Canvas.ALL_SAVE_FLAG);
            canvas.translate(getPaddingLeft(), getPaddingTop());
            bgActionBar.onDrawCrossfadeBackground(canvas);
            canvas.restore();

            canvas.saveLayerAlpha(AndroidUtilities.rectTmp, (int) ((1 - swipeProgress) * 0xFF), Canvas.ALL_SAVE_FLAG);
            canvas.translate(getPaddingLeft(), getPaddingTop());
            fgActionBar.onDrawCrossfadeBackground(canvas);
            canvas.restore();

            if (useBackDrawable) {
                AndroidUtilities.rectTmp.set(0, 0, getWidth(), bgActionBar.getY() + bgActionBar.getHeight());
                float progress = backDrawableForcedProgress != null ? backDrawableForcedProgress : swipeProgress;
                float bgAlpha = 1f - (bgActionBar.getY() / -(bgActionBar.getHeight() - AndroidUtilities.statusBarHeight));
                float fgAlpha = 1f - (fgActionBar.getY() / -(fgActionBar.getHeight() - AndroidUtilities.statusBarHeight));
                canvas.saveLayerAlpha(AndroidUtilities.rectTmp, (int) (AndroidUtilities.lerp(bgAlpha, fgAlpha, 1f - swipeProgress) * 0xFF), Canvas.ALL_SAVE_FLAG);
                canvas.translate(AndroidUtilities.dp(16 - 0.5f), AndroidUtilities.dp(16) + (fgActionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0));
                int color = ColorUtils.blendARGB(bgActionBar.getItemsColor(), fgActionBar.getItemsColor(), backDrawableReverse ? swipeProgress : 1f - swipeProgress);
                menuDrawable.setIconColor(color);
                menuDrawable.setRotation(backDrawableReverse ? progress : 1f - progress, false);
                menuDrawable.draw(canvas);
                canvas.restore();
            }

            AndroidUtilities.rectTmp.set(getPaddingLeft(), !AndroidUtilities.isTablet() ? AndroidUtilities.statusBarHeight : getPaddingTop(), getWidth() - getPaddingLeft(),  bgActionBar.getY() + getPaddingTop() + bgActionBar.getHeight() - containerViewBack.fragmentPanTranslationOffset);
            canvas.saveLayerAlpha(AndroidUtilities.rectTmp, (int) (swipeProgress * 0xFF), Canvas.ALL_SAVE_FLAG);
            canvas.translate(getPaddingLeft(), bgActionBar.getY() + getPaddingTop() - containerViewBack.fragmentPanTranslationOffset);
            bgActionBar.onDrawCrossfadeContent(canvas, false, useBackDrawable, swipeProgress);
            canvas.restore();

            AndroidUtilities.rectTmp.set(getPaddingLeft(), !AndroidUtilities.isTablet() ? AndroidUtilities.statusBarHeight : getPaddingTop(), getWidth() - getPaddingLeft(),  fgActionBar.getY() + getPaddingTop() + fgActionBar.getHeight() - containerView.fragmentPanTranslationOffset);
            canvas.saveLayerAlpha(AndroidUtilities.rectTmp, (int) ((1 - swipeProgress) * 0xFF), Canvas.ALL_SAVE_FLAG);
            canvas.translate(getPaddingLeft(), fgActionBar.getY() + getPaddingTop() - containerView.fragmentPanTranslationOffset);
            fgActionBar.onDrawCrossfadeContent(canvas, true, useBackDrawable, swipeProgress);
            canvas.restore();
        }
    }
    // --- Spring Animation ---
}