package com.ericpdf.app

/** Fully offline transcription-derived study notes, not an AI fact-checked summary.
 * This formatter preserves the spoken wording instead of inventing definitions.
 */
object LiveLectureNotes {
    fun fromSegments(segments:List<TranscriptSegment>):String {
        if(segments.isEmpty()) return ""
        val output=StringBuilder()
        var lastPage=-1
        for(segment in segments) {
            val phrase=segment.text.trim().replace(Regex("\\s+")," ")
            if(phrase.isBlank()) continue
            if(segment.page!=lastPage) {
                if(output.isNotEmpty()) output.append("\n")
                output.append("PAGE ").append(segment.page+1).append("\n")
                lastPage=segment.page
            }
            val seconds=segment.startMs.coerceAtLeast(0)/1000
            val clock="%02d:%02d:%02d".format(java.util.Locale.US,
                seconds/3600,(seconds/60)%60,seconds%60)
            output.append("• [").append(clock).append("] ").append(phrase).append("\n")
        }
        return output.toString()
    }
}
