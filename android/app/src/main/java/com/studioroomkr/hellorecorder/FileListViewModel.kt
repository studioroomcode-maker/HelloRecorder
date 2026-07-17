package com.studioroomkr.hellorecorder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import java.io.File

/** 파일 목록 어댑터가 그리는 한 줄. Header(검색·칩 등) / Day(그룹 헤더) / Row(파일) / Empty. */
sealed class FileListItem {
    object Header : FileListItem()
    data class Day(val dayKey: String, val pretty: String, val keys: List<String>, val count: Int) : FileListItem()
    data class Row(val file: File, val key: String) : FileListItem()
    data class Empty(val text: String) : FileListItem()
}

/**
 * 파일 목록 화면의 상태와 표시 모델 계산을 담는다. 검색어·선택·날짜/카테고리 필터·접힘 상태가
 * 여기 살아, 화면 회전이나 액티비티 recreate() 를 거쳐도 유지된다(예전엔 필드라 매번 초기화됐다).
 *
 * 뷰를 참조하지 않는다 — 데이터는 RecordingsRepository 로만 접근하고, 결과 모델(List<FileListItem>)만
 * 돌려준다. 실제 행 그리기는 액티비티/어댑터가 이 모델을 보고 한다.
 */
class FileListViewModel(app: Application) : AndroidViewModel(app) {

    val repo = RecordingsRepository(app)

    var searchQuery: String = ""
    var selectedDateKey: String? = null    // null = 전체 날짜
    var selectedCategory: String? = null   // null = 전체 카테고리

    val selectedKeys = HashSet<String>()
    val collapsedDays = HashSet<String>()          // 접힌 날짜
    private val initializedDays = HashSet<String>() // 접힘 기본값을 이미 정한 날짜

    val fileItems = ArrayList<FileListItem>()      // 현재 표시 모델
    val shownKeys = ArrayList<String>()            // 현재 목록에 걸린 모든 키(접힌 것 포함)

    /** 날짜 그룹 접힘 토글. */
    fun toggleCollapse(day: String) {
        if (collapsedDays.contains(day)) collapsedDays.remove(day) else collapsedDays.add(day)
    }

    /**
     * 표시 모델을 새로 계산해 fileItems 에 채우고 돌려준다. todayKey/isPro 는 화면에서 넘긴다.
     * 접힘 기본값: (필터 없음) 오늘만 펼침 / (날짜 선택 시) 그 날 펼침. 이후엔 사용자 토글을 유지.
     */
    fun buildItems(todayKey: String, isPro: Boolean): List<FileListItem> {
        fileItems.clear()
        fileItems.add(FileListItem.Header)
        shownKeys.clear()
        var shown = 0
        for (dayDir in repo.listDayDirs()) {
            val d = dayDir.name
            if (selectedDateKey != null && d != selectedDateKey) continue
            val files = repo.filesForDay(dayDir, searchQuery, selectedCategory, isPro)
            if (files.isEmpty()) continue

            val pretty = if (d.length == 8)
                "${d.substring(0, 4)}.${d.substring(4, 6)}.${d.substring(6, 8)}" else d
            val keys = files.map { repo.key(it) }
            shownKeys.addAll(keys)   // 접혀 있어도 선택 유지·전체선택 대상

            if (initializedDays.add(d)) {
                val defaultExpanded =
                    (selectedDateKey != null && d == selectedDateKey) ||
                        (selectedDateKey == null && d == todayKey)
                if (!defaultExpanded) collapsedDays.add(d)
            }

            fileItems.add(FileListItem.Day(d, pretty, keys, files.size))
            if (!collapsedDays.contains(d)) {
                for (i in files.indices) fileItems.add(FileListItem.Row(files[i], keys[i]))
            }
            shown += files.size
        }
        selectedKeys.retainAll(shownKeys.toSet())
        if (shown == 0) {
            fileItems.add(FileListItem.Empty(
                if (searchQuery.isEmpty()) I18n.t("아직 녹음된 파일이 없습니다.")
                else I18n.t("검색 결과가 없습니다.")
            ))
        }
        return fileItems
    }

    /** 재생 중인 키의 행 위치(어댑터 인덱스). 없으면 -1. */
    fun rowPositionForKey(key: String): Int =
        fileItems.indexOfFirst { it is FileListItem.Row && it.key == key }
}
