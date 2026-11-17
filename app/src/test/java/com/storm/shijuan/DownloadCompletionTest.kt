package com.storm.shijuan

import com.storm.shijuan.data.DownloadTask
import com.storm.shijuan.data.STATE_DONE
import com.storm.shijuan.data.STATE_ERROR
import com.storm.shijuan.data.ComicChapter
import com.storm.shijuan.data.NovelDownloadTask
import com.storm.shijuan.data.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadCompletionTest {
    @Test fun incompleteComicMustNotBeDone() {
        val task = DownloadTask(chapters = listOf(ComicChapter("一", "/1", 0)), state = STATE_ERROR)
        assertEquals(0f, task.percent)
        assertEquals(STATE_ERROR, task.state)
    }

    @Test fun incompleteNovelIsNotComplete() {
        val task = NovelDownloadTask(chapters = listOf(Chapter(name = "一", url = "/1", index = 0)), state = STATE_ERROR)
        assertEquals(STATE_ERROR, task.state)
    }
}
