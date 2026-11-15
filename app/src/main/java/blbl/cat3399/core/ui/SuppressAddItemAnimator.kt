package blbl.cat3399.core.ui

import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * Keeps the default remove/change behaviour but never animates inserted items.
 *
 * Paging appends a whole page at once; the default animator then runs one alpha animation per
 * added card. On a TV box that lands on the same frames as the insert layout pass and is a
 * reliable source of dropped frames, while the cards are usually inserted below the fold anyway.
 * Item appearance is carried by the cover's own fade-in instead.
 */
class SuppressAddItemAnimator : DefaultItemAnimator() {
    init {
        supportsChangeAnimations = false
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean = false
}
