package com.friendspharma.app.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A report as a grid the screen can render without knowing what it contains.
 *
 * <p>Columns are described rather than assumed, so one table component renders
 * every report and adding a report needs no frontend change. The alternative — a
 * typed DTO per report — means thirty near-identical shapes and thirty places
 * for the column order to drift from the header.
 *
 * <p>Totals are computed server-side over the whole result, not the page. A
 * footer that sums only what is on screen is worse than no footer: it looks like
 * an answer.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReportGridResponse {

    private String id;
    private String title;
    /** What the figures mean, where that is not obvious from the column names. */
    private String note;

    private List<Column> columns;
    private List<Map<String, Object>> rows;

    /** Row count across the whole report, before any client-side filtering. */
    private int rowCount;

    /** Column key → total, for the columns where a total is meaningful. */
    private Map<String, Object> totals;

    /**
     * The window the report actually ran over.
     *
     * <p>Sent because the report may have chosen it. A blank date filter used to
     * mean every invoice ever raised; it now means the last ninety days, and a
     * screen that shows ninety days while implying all of history is lying to
     * whoever reads the total at the bottom.
     */
    private LocalDate appliedFrom;
    private LocalDate appliedTo;

    /** True where the window above was the default rather than the caller's. */
    private Boolean windowDefaulted;

    /** True where the result hit the row cap, so more exists than is shown. */
    private Boolean truncated;

    /** Which filters this report actually honours, so the UI shows only those. */
    private List<String> supportedFilters;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Column {
        private String key;
        private String label;
        /** text | number | money | percent | date — drives alignment and format. */
        private String type;
        /** True where a column total belongs. Counts and money, not rates. */
        private Boolean total;
    }
}
