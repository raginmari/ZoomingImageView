package com.reimartwelker.view;

/*
 * Copyright 2012 Reimar Twelker
 * Free to use under the MIT license.
 * http://www.opensource.org/licenses/mit-license.php
 */

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.FloatMath;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;
import android.widget.ImageView;

/**
 * Adds scrolling and zooming to the {@link android.widget.ImageView} class. The content image can be scrolled in the x and y directions. The class supports pinch zooming of the content image.
 * Use {@link ScrollListener} and {@link ZoomListener} to keep track of when a scrolling or zooming gesture starts and ends.
 * <br><br>
 * The class supports animated zooming of the content image. Use {@link zoomToRect(RectF, int, float)} to specify a rectangle in the content image and set the timing (e.g. the duration of the animation). By means of the
 * {@link ZoomAnimationListener} interface, you are notified about the start and the end of the animation.
 * 
 * @author reimartwelker
 *
 */
public class ZoomingImageView extends ImageView
{
	public interface ScrollListener
	{	
		public void onScrollingStarted(ZoomingImageView view);
		public void onScrollingEnded(ZoomingImageView view, int x, int y);
	}
	
	public interface ZoomListener
	{
		public void onZoomingStarted(ZoomingImageView view);
		public void onZoomingEnded(ZoomingImageView view, float scale);
	}
	
	public interface ZoomAnimationListener
	{
		public void onZoomAnimationStarted(ZoomingImageView view);
		public void onZoomAnimationFinished(ZoomingImageView view);
	}
	
	public interface LayoutListener
	{
		public void onLayoutFinished(ZoomingImageView view, int w, int h);
	}
	
	/**
	 * The timing value passed to {@link zoomToRect(RectF, int, float)} is the absolute duration of the animation
	 */
	public static final int TIMING_DURATION = 0;
	
	/**
	 * The timing value passed to {@link zoomToRect(RectF, int, float)} is the per-pixel speed of the animation.
	 * See constants SLOW and FAST. 
	 */
	public static final int TIMING_PROPORTIONAL = 1;
	
	/**
	 * Slow per-pixel animation speed in {@code TIMING_PROPORTIONAL} mode
	 */
	public static final float SLOW = 0.01f;
	
	/**
	 * Fast per-pixel animation speed in {@code TIMING_PROPORTIONAL} mode
	 */
	public static final float FAST = 0.001f;
	
	/**
	 * Sets the minimum zoom level to the scale that fits the content into the available area. Default value.
	 */
	public static final int FIT = -1;
	
	// Animation message ID
	private static final int MESSAGE_ZOOM = 1;
	
	// Animation frame time
	private static final long FRAME_TIME = (1000 / 60);
	
	// Used to determine the current translation and scale
	private final float P[] = new float[] {0, 0, 1, 0};
	private final float Q[] = new float[4];
	
	// Holds the values of a transform
	private final float M[] = new float[9];
		
	// Holds a rectangle
	private final RectF RECT = new RectF();
	
	// Touch mode constants
	private static final int NONE = 0;
	private static final int DRAG = 1;
	private static final int ZOOM = 2;
	
	private int mTouchMode;
	private int mActivePointerId;
	private float mMinZoom;
	private float mMaxZoom;
	private Matrix mTransform;
	private Matrix mSavedTransform;
	private PointF mGestureStartPoint;
	private ScaleGestureDetector mPinchZoomDetector;
	private ScrollListener mScrollListener;
	private ZoomListener mZoomListener;
	private LayoutListener mLayoutListener;
	private float mAnimationDuration;
	private float mAnimationTime;
	private long mAnimationLastTime;
	private RectF mFromRect;
	private RectF mToRect;
	private Handler mAnimator = new ZoomAnimator();
	private ZoomAnimationListener mAnimationListener;
	
	public ZoomingImageView(Context context)
	{
		this(context, null);
	}
	
	public ZoomingImageView(Context context, AttributeSet attrs)
	{
		super(context, attrs);
		setScaleType(ScaleType.MATRIX);
		
		mTouchMode = NONE;
		mActivePointerId = -1;
		mTransform = new Matrix();
		mSavedTransform = new Matrix();
		mGestureStartPoint = new PointF();
		mPinchZoomDetector = new ScaleGestureDetector(context, new PinchZoomListener());
		
		if (attrs != null)
		{
			TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ZoomingImageView, 0, 0);
			mMinZoom = a.getFloat(R.styleable.ZoomingImageView_minZoom, FIT);
			mMaxZoom = a.getFloat(R.styleable.ZoomingImageView_maxZoom, Float.MAX_VALUE);
			a.recycle();
		}
		else
		{
			mMinZoom = FIT;
			mMaxZoom = Float.MAX_VALUE;
		}
	}
	
	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh)
	{
		super.onSizeChanged(w, h, oldw, oldh);
		if ((mLayoutListener != null) && (w > 0) && (h > 0))
		{
			mLayoutListener.onLayoutFinished(this, w, h);
		}
	}
	
	public void setLayoutListener(LayoutListener listener)
	{
		mLayoutListener = listener;
	}
	
	public void setAnimationListener(ZoomAnimationListener listener)
	{
		mAnimationListener = listener;
	}
	
	public void setScrollListener(ScrollListener listener)
	{
		mScrollListener = listener;
	}
	
	public void setZoomListener(ZoomListener listener)
	{
		mZoomListener = listener;
	}
	
	protected float getViewportWidth()
	{
		return getWidth() - getPaddingLeft() - getPaddingRight();
	}
	
	protected float getViewportHeight()
	{
		return getHeight() - getPaddingTop() - getPaddingBottom();
	}
	
	/**
	 * Returns the rectangular region of the content that is currently visible in the view.
	 * 
	 * @return A rectangle in content coordinates
	 */
	public RectF getVisibleRect()
	{
		final float q[] = Q;
		mTransform.mapPoints(q, P);
		final float scale = q[2] - q[0];
		final float x = -q[0] / scale;
		final float y = -q[1] / scale;
		final float w = getViewportWidth() / scale;
		final float h = getViewportHeight() / scale;
		return new RectF(x, y, x + w, y + h);
	}
	
	/*
	 * Adjusts the width/height ratio of the given rectangle so that it matches the
	 * ratio of the view. The area of the resulting rectangle is never smaller than that of the original rectangle.
	 * 
	 * @Returns The modified input rectangle
	 */
	private RectF fitInsideViewport(RectF rect)
	{	
		final float viewRatio = getViewportWidth() / getViewportHeight();
		final float rw = rect.width();
		final float rh = rect.height();
		final float rectRatio = rw / rh;
		if (rectRatio < viewRatio)
		{
			final float excessWidth = 0.5f * (rw * (viewRatio / rectRatio) - rw);
			rect.inset(-excessWidth, 0);
		}
		else if (rectRatio > viewRatio)
		{
			final float excessHeight = 0.5f * (rh * (rectRatio / viewRatio) - rh);
			rect.inset(0, -excessHeight);
		}
		return rect;
	}
	
	/**
	 * Zooms the view so that the content within the given rectangle fills the view.
	 * The rectangular region is specified in content coordinates. Not animated.
	 *  
	 * @param rect The rectangular region of the content to be shown
	 */
	public void showRect(RectF rect)
	{
		final float vw = getViewportWidth();
		final float vh = getViewportHeight();
		final float rw = rect.width();
		final float rh = rect.height();
		final float scale = Math.min(vw / rw, vh / rh);
		mTransform.setTranslate(-rect.left, -rect.top);
		mTransform.postScale(scale, scale);
		checkTransform(mTransform);
		invalidate();
	}
	
	/**
	 * Zooms the view so that the content within the given rectangle fills the view.
	 * The rectangular region is specified in content coordinates.
	 * <br><br>
	 * There are two different ways to set the duration of the animation: 
	 * <ul>
	 * <li>If the {@code mode} is {@code TIMING_DURATION}, the {@code timing} parameter specifies the duration of the animation in seconds.</li>
	 * <li>If the {@code mode} is {@code TIMING_PROPORTIONAL}, the {@code timing} parameter specifies the duration of the animation relative to 
	 * the change in zoom requested. The display density is taken into account. Use {@code SLOW} or {@code FAST}, for example.</li>
	 * </ul> 
	 * 
	 * @param rect The rectangular region of the content to be shown
	 * @param mode The timing mode
	 * @param timing The timing parameter
	 */
	public void zoomToRect(RectF rect, int mode, float timing)
	{
		// Stop the current animation
		mAnimator.removeMessages(MESSAGE_ZOOM);
		
		if (timing <= 0)
		{
			showRect(rect);
			return;
		}
		
		// Set the start and end rectangles of the animation
		mFromRect = getVisibleRect();
		mToRect = fitInsideViewport(rect);
		
		// Start the animation
		mAnimationTime = 0;
		if (mode == TIMING_DURATION)
		{
			mAnimationDuration = timing;
		}
		else // TIMING_PROPORTIONAL
		{
			final float dx = mToRect.left - mFromRect.left;
			final float dy = mToRect.top - mFromRect.top;
			final float ds = mToRect.width() - mFromRect.width();
			final float density = getContext().getResources().getDisplayMetrics().density;
			final float length = (float)FloatMath.sqrt(dx * dx + dy * dy + ds * ds) / density;
			mAnimationDuration = timing * length;
		}
		
		if (mAnimationListener != null)
		{
			mAnimationListener.onZoomAnimationStarted(this);
		}
		
		mAnimationLastTime = SystemClock.uptimeMillis();
		mAnimator.sendMessageAtTime(mAnimator.obtainMessage(MESSAGE_ZOOM), mAnimationLastTime + FRAME_TIME);
	}
	
	/**
	 * Zooms the content so that it fits inside the view. Not animated.
	 */
	public void zoomToFit()
	{
		this.zoomToFit(false, 0, 0);
	}
	
	/**
	 * Zooms the content so that it fits inside the view. The change is animated if
	 * {@code animated} is {@code true}.
	 * 
	 * @param animated If {@code true}, then the change in zoom is animated
	 * @param timing The timing parameter
	 * @param mode The timing mode
	 * @see zoomToRect(RectF, float, int)
	 */
	public void zoomToFit(boolean animated, int mode, float timing)
	{
		Drawable d = getDrawable();
		if (d != null)
		{
			RectF rect = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
			if (animated)
			{
				zoomToRect(rect, mode, timing);
			}
			else
			{
				showRect(rect);
			}
		}
	}
	
	protected Matrix getTransform()
	{
		return mTransform;
	}
	
	protected float getScaleToFit()
	{
		final float vw = getViewportWidth();
		final float vh = getViewportHeight();
		final Drawable d = getDrawable();
		final float dw = d.getIntrinsicWidth();
		final float dh = d.getIntrinsicHeight();
		return Math.min(Math.min(1, vw / dw), vh / dh);
	}
	
	public void setMinZoom(float minZoom)
	{
		mMinZoom = minZoom;
	}
	
	public float getMinZoom()
	{
		if (mMinZoom == FIT)
		{
			mMinZoom = getScaleToFit();
		}
		return mMinZoom;
	}
	
	public void setMaxZoom(float maxZoom)
	{
		mMaxZoom = maxZoom;
	}
	
	protected float getMaxZoom()
	{
		if (mMaxZoom == FIT)
		{
			mMaxZoom = getScaleToFit();
		}
		return mMaxZoom;
	}
	
	protected void notifyScrollingStarted()
	{
		if (mScrollListener != null)
		{
			mScrollListener.onScrollingStarted(this);
		}
	}
	
	protected void notifyScrollingFinished()
	{
		if (mScrollListener != null)
		{
			mScrollListener.onScrollingEnded(this, getContentX(), getContentY());
		}
	}
	
	protected void notifyZoomingStarted()
	{
		if (mZoomListener != null)
		{
			mZoomListener.onZoomingStarted(this);
		}
	}
	
	protected void notifyZoomingFinished()
	{
		if (mZoomListener != null)
		{
			mZoomListener.onZoomingEnded(this, getZoom());
		}
	}
	
	/**
	 * Returns the scale currently applied to the content.
	 * 
	 * @return The current scale
	 */
	public float getZoom()
	{
		mTransform.getValues(M);
		return M[Matrix.MSCALE_X];
	}
	
	/**
	 * Returns the current translation in x applied to the content. Not scaled.
	 * 
	 * @return The x translation of the content
	 */
	public int getContentX()
	{
		mTransform.getValues(M);
		return (int)M[Matrix.MTRANS_X];
	}
	
	/**
	 * Returns the current translation in y applied to the content. Not scaled.
	 * 
	 * @return The y translation of the content
	 */
	public int getContentY()
	{
		mTransform.getValues(M);
		return (int)M[Matrix.MTRANS_Y];
	}
	
	/**
	 * Returns the width of the content. Not scaled.
	 * 
	 * @return The content width in pixels.
	 * @see getScaledContentWidth()
	 */
	public int getContentWidth()
	{
		return getDrawable().getIntrinsicWidth();
	}
	
	/**
	 * Returns the scaled content width.
	 * 
	 * @return The scaled content width in pixels
	 */
	public float getScaledContentWidth()
	{
		return getZoom() * getDrawable().getIntrinsicWidth();
	}
	
	/**
	 * Returns the height of the content. Not scaled.
	 * 
	 * @return The content height in pixels.
	 * @see getScaledContentHeight()
	 */
	public int getContentHeight()
	{
		return getDrawable().getIntrinsicHeight();
	}
	
	/**
	 * Returns the scaled content height.
	 * 
	 * @return The scaled content height in pixels
	 */
	public float getScaledContentHeight()
	{
		return getZoom() * getDrawable().getIntrinsicHeight();
	}
	
	/**
	 * Sets the scale applied to the content.
	 * 
	 * @param zoom The scale to apply
	 */
	public void setZoom(float zoom)
	{
		setMode(NONE);
		final float m[] = M;
		mTransform.getValues(m);
		m[Matrix.MSCALE_X] = zoom;
		m[Matrix.MSCALE_Y] = zoom;
		mTransform.setValues(m);
		checkTransform(mTransform);
		invalidate();
	}
	
	/**
	 * Sets the zoom to the minimal zoom.
	 * 
	 * @see getMinZoom()
	 */
	public void resetZoom()
	{
		setZoom(getMinZoom());
	}
	
	/**
	 * Sets the translation applied to the content.
	 * 
	 * @param x The x translation
	 * @param y The y translation
	 */
	public void setContentPosition(float x, float y)
	{
		setMode(NONE);
		final float m[] = M;
		mTransform.getValues(m);
		m[Matrix.MTRANS_X] = x;
		m[Matrix.MTRANS_Y] = y;
		mTransform.setValues(m);
		checkTransform(mTransform);
		invalidate();
	}
	
	/**
	 * Centers the content in the view.
	 */
	public void centerContent()
	{
		float x = (getViewportWidth() - getScaledContentWidth()) / 2;
		float y = (getViewportHeight() - getScaledContentHeight()) / 2;
		setContentPosition(getPaddingLeft() + x, getPaddingTop() + y);
	}
	
	protected Matrix checkTransform(Matrix t)
	{
		final float m[] = M;
		t.getValues(m);
		
		final float scale = m[Matrix.MSCALE_X];
		final float contentW = scale * getDrawable().getIntrinsicWidth();
		final float contentH = scale * getDrawable().getIntrinsicHeight();
		
		// Get the top-left corner in the view and the size of the view
		final float viewW = getWidth() - getPaddingLeft() - getPaddingRight();
		final float viewH = getHeight() - getPaddingTop() - getPaddingBottom();
		
		// Fix the horizontal translation
		final float x = m[Matrix.MTRANS_X];
		final float minX = viewW - contentW;
		if (viewW > contentW)
		{
			// Center content
			m[Matrix.MTRANS_X] = (viewW - contentW) / 2;
		}
		else
		{
			m[Matrix.MTRANS_X] = (x > 0 ? 0 : (x < minX ? minX : x));
		}
		
		// Fix the vertical translation
		final float y = m[Matrix.MTRANS_Y];
		final float minY = viewH - contentH;
		if (viewH > contentH)
		{
			// Center content
			m[Matrix.MTRANS_Y] = (viewH - contentH) / 2;
		}
		else
		{
			m[Matrix.MTRANS_Y] = (y > 0 ? 0 : (y < minY ? minY : y));
		}
		
		t.setValues(m);
		return t;
	}
	
	private void setMode(int newMode)
	{
		int oldMode = mTouchMode;
		if (newMode != oldMode)
		{ 
			if (oldMode == DRAG)
			{
				notifyScrollingFinished();
			}
			else if (oldMode == ZOOM)
			{
				notifyZoomingFinished();
			}
			
			mTouchMode = newMode;
			mSavedTransform.set(mTransform);
			
			if (newMode == DRAG)
			{
				notifyScrollingStarted();
			}
			else if (newMode == ZOOM)
			{
				notifyZoomingStarted();
			}
		}
	}
	
	@Override
	protected void onDraw(Canvas canvas)
	{	
		canvas.save();
		
		// Clip
		final float x1 = getPaddingLeft();
		final float y1 = getPaddingTop();
		final float x2 = getWidth() - getPaddingRight();
		final float y2 = getHeight() - getPaddingBottom();
		canvas.clipRect(x1, y1, x2, y2);
		
		// Transform
		canvas.translate(getPaddingLeft(), getPaddingTop());
		canvas.concat(mTransform);
		
		final Drawable d = getDrawable();
		if (d != null)
		{
			d.draw(canvas);
		}
		
		canvas.restore();
	}

	@Override
	public boolean onTouchEvent(MotionEvent event)
	{
		mPinchZoomDetector.onTouchEvent(event);
		
		final int action = event.getAction();
		switch (action & MotionEvent.ACTION_MASK)
		{
			case MotionEvent.ACTION_DOWN:
			{
				final float x = event.getX();
				final float y = event.getY();
				mGestureStartPoint.x = x;
				mGestureStartPoint.y = y;
				mActivePointerId = event.getPointerId(0);
				setMode(DRAG);
			} break;
			
			case MotionEvent.ACTION_MOVE:
			{
				if (mTouchMode == DRAG)		// Pinch zoom is handled by the gesture detector
				{	
					final int pointerIndex = event.findPointerIndex(mActivePointerId);
					final float x = event.getX(pointerIndex);
					final float y = event.getY(pointerIndex);
					
					// Update translation
					float dx = x - mGestureStartPoint.x;
					float dy = y - mGestureStartPoint.y;
					mTransform.set(mSavedTransform);
					mTransform.postTranslate(dx, dy);
					checkTransform(mTransform);
					
					invalidate();
				}
			} break;
			
			case MotionEvent.ACTION_POINTER_DOWN:
			{
				
			} break;
			
			case MotionEvent.ACTION_POINTER_UP:
//			{
//				final int liftedPointerIndex = (action & MotionEvent.ACTION_POINTER_ID_MASK) >> MotionEvent.ACTION_POINTER_ID_SHIFT;
//				final int liftedPointerID = event.getPointerId(liftedPointerIndex);
//				if (liftedPointerID == mActivePointerID)
//				{
//					// The tracked pointer has been lifter. Select another one (there must be another one!)
//					final int newPointerIndex = liftedPointerIndex == 0 ? 1 : 0;
//					mTouchDownPoint.x = event.getX(newPointerIndex);
//					mTouchDownPoint.y = event.getY(newPointerIndex);
//					mActivePointerID = event.getPointerId(newPointerIndex);
//				}
//				setMode(DRAG);
//			} break;
			
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
			case MotionEvent.ACTION_OUTSIDE:
			{
				setMode(NONE);
			}
		}
		
		return true;
	}
	
	private final class PinchZoomListener implements OnScaleGestureListener
	{
		@Override
		public boolean onScale(ScaleGestureDetector detector)
		{
			final float minZoom = getMinZoom();
			final float maxZoom = getMaxZoom();
			float deltaScale = detector.getScaleFactor();
			
			// Update scale
			mTransform.getValues(M);
			final float currentScale = M[Matrix.MSCALE_X];
			final float newScale = currentScale * deltaScale;
			if (newScale < minZoom)
			{
				deltaScale = minZoom / currentScale;
			}
			else if (newScale > maxZoom)
			{
				deltaScale = maxZoom / currentScale;
			}
			mTransform.postScale(deltaScale, deltaScale, detector.getFocusX(), detector.getFocusY());
			checkTransform(mTransform);
			invalidate();
			return true;
		}

		@Override
		public boolean onScaleBegin(ScaleGestureDetector detector)
		{	
			setMode(ZOOM);
			return true;
		}

		@Override
		public void onScaleEnd(ScaleGestureDetector detector)
		{
			setMode(NONE);
		}
	}
	
	private void incrementAnimation()
	{
		final long now = SystemClock.uptimeMillis();
		final float dt = Math.min(2 * FRAME_TIME, (now - mAnimationLastTime) / 1000.0f);
		
		boolean over = false;
		mAnimationTime += dt;
		if (mAnimationTime >= mAnimationDuration)
		{
			mAnimationTime = mAnimationDuration;
			over = true;
		}
		
		// Interpolate from the start to the end rectangle
		final float a = mAnimationTime / mAnimationDuration;	// '1' if (over == true)
		final RectF fromRect = mFromRect;
		final RectF toRect = mToRect;
		final RectF rect = RECT;
		rect.left = fromRect.left + a * (toRect.left - fromRect.left);
		rect.top = fromRect.top + a * (toRect.top - fromRect.top);
		rect.right = fromRect.right + a * (toRect.right - fromRect.right);
		rect.bottom = fromRect.bottom + a * (toRect.bottom - fromRect.bottom);
		showRect(rect);
		
		if (over)
		{
			// Stop animating
			mAnimator.removeMessages(MESSAGE_ZOOM);
			
			if (mAnimationListener != null)
			{
				mAnimationListener.onZoomAnimationFinished(this);
			}
		}
		else
		{
			// Schedule next frame
			mAnimationLastTime = now;
			mAnimator.sendMessageAtTime(mAnimator.obtainMessage(MESSAGE_ZOOM), mAnimationLastTime + FRAME_TIME);
		}
	}
	
	private class ZoomAnimator extends Handler 
	{
        public void handleMessage(Message m) 
        {
            switch (m.what) 
            {
                case MESSAGE_ZOOM:
                    incrementAnimation();
                    break;
            }
        }
    }
}
