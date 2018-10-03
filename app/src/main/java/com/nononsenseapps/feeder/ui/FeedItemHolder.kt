package com.nononsenseapps.feeder.ui

import android.content.Intent
import android.text.Spannable
import android.text.SpannableString
import android.text.style.TextAppearanceSpan
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.coroutines.BackgroundUI
import com.nononsenseapps.feeder.db.room.AppDatabase
import com.nononsenseapps.feeder.model.PreviewItem
import com.nononsenseapps.feeder.util.GlideUtils
import com.nononsenseapps.feeder.util.PREF_VAL_OPEN_WITH_BROWSER
import com.nononsenseapps.feeder.util.PREF_VAL_OPEN_WITH_READER
import com.nononsenseapps.feeder.util.PREF_VAL_OPEN_WITH_WEBVIEW
import com.nononsenseapps.feeder.util.PrefUtils
import com.nononsenseapps.feeder.util.PrefUtils.shouldOpenItemWith
import com.nononsenseapps.feeder.util.PrefUtils.shouldOpenLinkWith
import com.nononsenseapps.feeder.util.openLinkInBrowser
import kotlinx.coroutines.experimental.launch

// Provide a reference to the views for each data item
// Complex data items may need more than one view per item, and
// you provide access to all the views for a data item in a view holder
class FeedItemHolder(val view: View, private val dismissListener: DismissedListener) :
        ViewHolder(view), View.OnClickListener, ViewTreeObserver.OnPreDrawListener {
    private val TAG = "FeedItemHolder"
    private val titleTextView: TextView = view.findViewById<View>(R.id.story_snippet) as TextView
    val dateTextView: TextView = view.findViewById<View>(R.id.story_date) as TextView
    val authorTextView: TextView = view.findViewById<View>(R.id.story_author) as TextView
    val imageView: ImageView = view.findViewById<View>(R.id.story_image) as ImageView
    private val bgFrame: View = view.findViewById(R.id.swiping_item)
    private val checkLeft: View = view.findViewById(R.id.check_left)
    private val checkRight: View = view.findViewById(R.id.check_right)
    private val checkBg: View = view.findViewById(R.id.check_bg)

    var rssItem: PreviewItem? = null

    init {
        view.setOnClickListener(this)
        // Swipe handler
        view.setOnTouchListener(SwipeDismissTouchListener(view, null, object : SwipeDismissTouchListener.DismissCallbacks {
            override fun canDismiss(token: Any?): Boolean = rssItem != null

            override fun onDismiss(view: View, token: Any?) {
                dismissListener.onDismiss(rssItem)
            }

            /**
             * Called when a swipe is started.
             *
             * @param goingRight true if swiping to the right, false if left
             */
            override fun onSwipeStarted(goingRight: Boolean) {
                dismissListener.onSwipeStarted()

                val typedValue = TypedValue()
                if (PrefUtils.isNightMode(view.context)) {
                    view.context?.theme?.resolveAttribute(R.attr.nightBGColor,
                            typedValue, true)
                } else {
                    view.context?.theme?.resolveAttribute(android.R.attr.windowBackground,
                            typedValue, true)
                }
                bgFrame.setBackgroundColor(typedValue.data)
                checkBg.visibility = View.VISIBLE
                if (goingRight) {
                    checkLeft.visibility = View.VISIBLE
                } else {
                    checkRight.visibility = View.VISIBLE
                }
            }

            /**
             * Called when user doesn't swipe all the way.
             */
            override fun onSwipeCancelled() {
                dismissListener.onSwipeCancelled()

                checkBg.visibility = View.INVISIBLE
                checkLeft.visibility = View.INVISIBLE
                checkRight.visibility = View.INVISIBLE

                bgFrame.background = null
            }

            /**
             * @return the subview which should move
             */
            override fun getSwipingView(): View {
                return bgFrame
            }
        }))
    }

    fun resetView() {
        checkBg.visibility = View.INVISIBLE
        checkLeft.visibility = View.INVISIBLE
        checkRight.visibility = View.INVISIBLE
        bgFrame.clearAnimation()
        bgFrame.alpha = 1.0f
        bgFrame.translationX = 0.0f
        bgFrame.background = null
    }

    fun fillTitle(forceRead: Boolean = false) {
        titleTextView.visibility = View.VISIBLE
        rssItem?.let { rssItem ->
            // \u2014 is a EM-dash, basically a long version of '-'
            val temps = if (rssItem.plainSnippet.isEmpty())
                rssItem.plainTitle
            else
                rssItem.plainTitle + " \u2014 " + rssItem.plainSnippet + "\u2026"
            val textSpan = SpannableString(temps)

            textSpan.setSpan(TextAppearanceSpan(view.context, R.style.TextAppearance_ListItem_Body),
                    rssItem.plainTitle.length, temps.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

            if (rssItem.unread && !forceRead) {
                textSpan.setSpan(TextAppearanceSpan(view.context, R.style.TextAppearance_ListItem_Title),
                        0, rssItem.plainTitle.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                textSpan.setSpan(TextAppearanceSpan(view.context, R.style.TextAppearance_ListItem_Title_Read),
                        0, rssItem.plainTitle.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            titleTextView.text = textSpan
        }
    }

    /**
     * OnItemClickListener replacement.
     *
     *
     * If a feeditem does not have any content,
     * then it opens the link in the browser directly.
     *
     * @param view
     */
    override fun onClick(view: View) {
        val context = view.context
        if (context != null) {
            val defaultOpenItemWith = shouldOpenItemWith(context)

            val openItemWith = when (defaultOpenItemWith) {
                PREF_VAL_OPEN_WITH_READER -> {
                    if (rssItem?.plainSnippet?.isNotEmpty() == true) {
                        defaultOpenItemWith
                    } else {
                        shouldOpenLinkWith(context)
                    }
                }
                else -> defaultOpenItemWith
            }

            when (openItemWith) {
                PREF_VAL_OPEN_WITH_BROWSER, PREF_VAL_OPEN_WITH_WEBVIEW -> {
                    // Mark as read
                    val db = AppDatabase.getInstance(context)
                    rssItem?.id?.let {
                        launch(BackgroundUI) {
                            db.feedItemDao().markAsRead(it)
                        }
                    }

                    when (openItemWith) {
                        PREF_VAL_OPEN_WITH_BROWSER -> {
                            // Open in browser since no content was posted
                            rssItem?.link?.let { link ->
                                openLinkInBrowser(context, link)
                            }
                        }
                        else -> {
                            val intent = Intent(context, ReaderWebViewActivity::class.java)
                            intent.putExtra(SHOULD_FINISH_BACK, true)
                            rssItem?.let {
                                intent.putExtra(ARG_URL, it.link)
                                intent.putExtra(ARG_ENCLOSURE, it.enclosureLink)
                            }
                            context.startActivity(intent)
                        }
                    }
                }
                else -> {
                    val i = Intent(context, ReaderActivity::class.java)
                    i.putExtra(SHOULD_FINISH_BACK, true)
                    rssItem?.let {
                        ReaderActivity.setRssExtras(i, it)
                    }

                    context.startActivity(i)
                }
            }
        }
    }

    /**
     * Called when item has been measured, it is now the time to insert the image.
     *
     * @return Return true to proceed with the current drawing pass, or false to cancel.
     */
    override fun onPreDraw(): Boolean {
        val context = view.context
        if (context != null) {
            rssItem?.let { rssItem ->
                try {
                    GlideUtils.glide(context, rssItem.imageUrl,
                            PrefUtils.shouldLoadImages(context))
                            .centerCrop()
                            .error(R.drawable.placeholder_image_list)
                            .into(imageView)
                } catch (e: IllegalArgumentException) {
                    // Could still happen if we have a race-condition?
                    Log.d(TAG, e.localizedMessage)
                }
            }
        }

        // Remove as listener
        itemView.viewTreeObserver.removeOnPreDrawListener(this)
        return true
    }
}

interface DismissedListener {
    fun onDismiss(item: PreviewItem?)
    fun onSwipeStarted()
    fun onSwipeCancelled()
}
