package co.hy.wifidelivery.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import co.hy.wifidelivery.databinding.ItemMemberBinding
import co.hy.wifidelivery.model.DeliveryType
import co.hy.wifidelivery.model.Member

class MemberAdapter(
    private val onClick: (Member) -> Unit,
    private val onLongClick: (Member) -> Unit
) : RecyclerView.Adapter<MemberAdapter.VH>() {

    private val items = mutableListOf<Member>()

    fun submit(list: List<Member>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemMemberBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.binding.txtName.text =
            if (m.routeOrder > 0) "${m.routeOrder}. ${m.name}" else m.name
        holder.binding.txtDetail.text = if (m.deliveryType == DeliveryType.OFFICE) {
            buildString {
                append("사무실")
                if (m.zoneId == null) append(" · 구역 미지정") else append(" · 구역 지정됨")
                if (m.hasTag) append(" · NFC")
                if (m.hasTag) append(" · NFC")
                if (!m.wifiAutoDetect) append(" · Wi-Fi 끔")
                if (!m.notifyEnabled) append(" · 문자 끔")
            }
        } else if (m.deliveryType == DeliveryType.MANUAL) {
            "수동 처리" + if (!m.notifyEnabled) " · 문자 끔" else ""
        } else if (m.hasSignature) {
            buildString {
                append("AP %d개 · %d라운드".format(m.signature.size, m.scanRounds))
                if (m.pressureIndex != null) append(" · 기압 %.2f".format(m.pressureIndex))
                append(" · 갱신 ${MainActivity.formatTime(m.updatedAt)}")
                if (m.hasTag) append(" · NFC")
                if (!m.wifiAutoDetect) append(" · Wi-Fi 끔")
                if (!m.notifyEnabled) append(" · 문자 끔")
            }
        } else {
            buildString {
                append(if (m.hasTag) "NFC 태그 등록됨" else "서명 미수집 — 탭해서 수집")
                if (!m.notifyEnabled) append(" · 문자 끔")
            }
        }
        holder.itemView.setOnClickListener { onClick(m) }
        holder.itemView.setOnLongClickListener { onLongClick(m); true }
    }
}
