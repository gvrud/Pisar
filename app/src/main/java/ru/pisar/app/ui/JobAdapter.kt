package ru.pisar.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ru.pisar.app.R
import ru.pisar.app.databinding.ItemJobBinding
import ru.pisar.app.model.Job
import ru.pisar.app.util.Format

data class JobRow(val job: Job, val query: String)

class JobAdapter(
    private val onClick: (Job) -> Unit,
) : ListAdapter<JobRow, JobAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemJobBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemJobBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: JobRow) {
            val context = binding.root.context
            val job = row.job
            val highlightColor = ContextCompat.getColor(context, R.color.highlight)

            binding.title.text = Highlight.highlight(job.title, row.query, highlightColor)

            val meta = buildString {
                append(Format.dateTime(job.createdAt))
                if (job.durationMs > 0) append(" · ").append(Format.duration(job.durationMs))
            }
            binding.meta.text = meta

            binding.status.text = statusLabel(context, job)
            binding.status.setTextColor(ContextCompat.getColor(context, statusColor(job.status)))

            val active = !job.status.isFinished
            binding.progress.visibility = if (active) View.VISIBLE else View.GONE
            binding.progress.progress = job.progress

            val snippet = Highlight.snippet(job.text.orEmpty(), row.query)
            if (snippet != null) {
                binding.snippet.visibility = View.VISIBLE
                binding.snippet.text = Highlight.highlight(snippet, row.query, highlightColor)
            } else {
                binding.snippet.visibility = View.GONE
            }

            binding.root.setOnClickListener { onClick(job) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<JobRow>() {
        override fun areItemsTheSame(oldItem: JobRow, newItem: JobRow): Boolean =
            oldItem.job.id == newItem.job.id && oldItem.query == newItem.query

        override fun areContentsTheSame(oldItem: JobRow, newItem: JobRow): Boolean =
            oldItem == newItem
    }

    companion object {

        fun statusLabel(context: Context, job: Job): String = when (job.status) {
            Job.Status.QUEUED -> context.getString(R.string.status_queued)
            Job.Status.PREPARING -> context.getString(R.string.status_preparing)
            Job.Status.DECODING -> context.getString(R.string.status_decoding)
            Job.Status.TRANSCRIBING -> context.getString(R.string.status_transcribing)
            Job.Status.SUMMARIZING -> context.getString(R.string.status_summarizing)
            Job.Status.DONE -> context.getString(R.string.status_done)
            Job.Status.ERROR -> job.error ?: context.getString(R.string.status_error)
            Job.Status.CANCELED -> context.getString(R.string.status_canceled)
        }

        fun statusColor(status: Job.Status): Int = when (status) {
            Job.Status.ERROR -> R.color.err
            Job.Status.DONE -> R.color.ok
            Job.Status.CANCELED -> R.color.ink_secondary
            else -> R.color.brand
        }
    }
}
