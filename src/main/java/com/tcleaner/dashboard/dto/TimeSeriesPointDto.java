package com.tcleaner.dashboard.dto;

/**
 * Точка временного ряда для графиков Chart.js.
 * {@code period} — строка-bucket ({@code "2026-04-15"} / {@code "2026-04"} / {@code "2026-W15"}),
 * {@code value} — агрегированное значение (число экспортов, сообщений или байт).
 */
public record TimeSeriesPointDto(String period, long value) {}
