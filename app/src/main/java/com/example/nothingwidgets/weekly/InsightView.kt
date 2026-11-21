package com.aurikqq.noswidgets.weekly

import android.content.Context
import android.graphics.*
import android.view.View
import android.view.MotionEvent
import android.text.StaticLayout
import android.text.TextPaint

class InsightView(context: Context, private val values: List<Long?>, private val dismiss: () -> Unit) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init { contentDescription = message() + ". " + context.getString(com.aurikqq.noswidgets.R.string.a11y_close_insight); isClickable = true }
    private fun message(): String {
        if (values.any { it == null }) return context.getString(com.aurikqq.noswidgets.R.string.widget_insight_missing)
        val total = values.filterNotNull().sum(); val average = total / 7
        return context.getString(com.aurikqq.noswidgets.R.string.widget_insight_text, average/60, average%60, total/60, total%60)
    }
    override fun onDraw(c: Canvas) {
        val scale = kotlin.math.min(width/1080f,height/2392f)
        c.save(); c.translate((width-1080*scale)/2,(height-2392*scale)/2); c.scale(scale,scale)
        p.shader = LinearGradient(63f,584f,1000f,1808f,intArrayOf(Color.rgb(76,148,234),Color.rgb(162,182,207)),null,Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(63f,584f,1017f,1808f),55f,55f,p); p.shader=null
        p.color=Color.WHITE;p.typeface=Typeface.create("monospace",Typeface.BOLD);p.textSize=42f;c.drawText(context.getString(com.aurikqq.noswidgets.R.string.widget_insight),104f,673f,p)
        p.strokeWidth=4f
        for(i in 0..3){val a=i*Math.PI/4;val x=kotlin.math.cos(a).toFloat()*23;val y=kotlin.math.sin(a).toFloat()*23;c.drawLine(949-x,655-y,949+x,655+y,p)}
        val textPaint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;textSize=72f;typeface=Typeface.create("serif",0)}
        c.save();c.translate(104f,750f);StaticLayout.Builder.obtain(message(),0,message().length,textPaint,860).setIncludePad(false).build().draw(c);c.restore()
        p.color=Color.rgb(35,35,35);c.drawCircle(540f,2132f,63f,p);p.color=Color.WHITE;p.strokeWidth=4f;c.drawLine(522f,2114f,558f,2150f,p);c.drawLine(522f,2150f,558f,2114f,p)
        c.restore()
    }
    override fun onTouchEvent(event: MotionEvent):Boolean { if(event.action==MotionEvent.ACTION_UP){ val scale=kotlin.math.min(width/1080f,height/2392f); val x=(event.x-(width-1080*scale)/2)/scale; val y=(event.y-(height-2392*scale)/2)/scale; if(!RectF(63f,584f,1017f,1808f).contains(x,y)) performClick() };return true }
    override fun performClick():Boolean {super.performClick();dismiss();return true}
}
