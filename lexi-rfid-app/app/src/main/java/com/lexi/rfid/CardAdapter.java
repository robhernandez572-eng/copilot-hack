package com.lexi.rfid;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class CardAdapter extends RecyclerView.Adapter<CardAdapter.ViewHolder> {

    public interface OnCardClickListener {
        void onCardClick(CardData card);
        void onCardLongClick(CardData card);
    }

    private List<CardData> cards;
    private OnCardClickListener listener;

    public CardAdapter(List<CardData> cards, OnCardClickListener listener) {
        this.cards = cards;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_card, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CardData card = cards.get(position);
        holder.tvName.setText(card.getName());
        holder.tvUid.setText("UID: " + card.getUid());
        holder.tvType.setText(card.getTypeLabel());
        holder.tvTime.setText(card.getTimestamp());
        holder.itemView.setOnClickListener(v -> listener.onCardClick(card));
        holder.itemView.setOnLongClickListener(v -> { listener.onCardLongClick(card); return true; });
    }

    @Override
    public int getItemCount() { return cards.size(); }

    public void updateCards(List<CardData> newCards) {
        cards = newCards;
        notifyDataSetChanged();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvUid, tvType, tvTime;
        ViewHolder(View v) {
            super(v);
            tvName = v.findViewById(R.id.tvCardName);
            tvUid = v.findViewById(R.id.tvCardUid);
            tvType = v.findViewById(R.id.tvCardType);
            tvTime = v.findViewById(R.id.tvCardTime);
        }
    }
}
