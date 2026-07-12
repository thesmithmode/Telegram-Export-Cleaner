package com.tcleaner.dashboard.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Dashboard frontend assets")
class DashboardFrontendAssetsTest {

    private static final Path STATIC_DASHBOARD = Path.of("src/main/resources/static/dashboard");
    private static final Path USERS_JS = STATIC_DASHBOARD.resolve("js/pages/users.js");
    private static final Pattern CONFLICT_MARKER = Pattern.compile("(?m)^(<<<<<<<|=======|>>>>>>>)");

    @Test
    @DisplayName("репозиторий не содержит Git conflict markers в текстовых файлах")
    void sourceFilesDoNotContainConflictMarkers() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("."))) {
            List<Path> offenders = files
                    .filter(Files::isRegularFile)
                    .filter(DashboardFrontendAssetsTest::isRepositoryFile)
                    .filter(DashboardFrontendAssetsTest::isTextSourceFile)
                    .filter(path -> {
                        try {
                            return CONFLICT_MARKER.matcher(Files.readString(path, StandardCharsets.UTF_8)).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();

            assertThat(offenders).isEmpty();
        }
    }

    @Test
    @DisplayName("dashboard JavaScript парсится Node.js")
    void dashboardJavaScriptHasValidSyntax() throws Exception {
        try (Stream<Path> files = Files.walk(STATIC_DASHBOARD.resolve("js"))) {
            for (Path jsFile : files.filter(path -> path.toString().endsWith(".js")).toList()) {
                ProcessResult result = run("node", "--check", jsFile.toString());
                assertThat(result.exitCode())
                        .as("node --check %s%nstdout:%n%s%nstderr:%n%s", jsFile, result.stdout(), result.stderr())
                        .isZero();
            }
        }
    }

    @Test
    @DisplayName("users.js содержит единственную реализацию normalizeUsername")
    void usersJsDoesNotDuplicateCriticalFunctions() throws IOException {
        String source = Files.readString(USERS_JS);

        assertThat(source.split("function normalizeUsername\\(", -1).length - 1).isEqualTo(1);
    }

    @Test
    @DisplayName("users.js загружает /dashboard/api/stats/users и рендерит пустой и заполненный список")
    void usersJsLoadsAndRendersUsers() throws Exception {
        String usersSource = Files.readString(USERS_JS, StandardCharsets.UTF_8);
        String harness = """
                (async () => {
                const vm = require('node:vm');
                const source = %s;

                class Node {
                  constructor(tag, attrs = {}, children = []) {
                    this.tag = tag;
                    this.attrs = attrs || {};
                    this.children = children;
                    this.textContent = attrs?.text || '';
                    this.listeners = {};
                    this.colSpan = attrs?.colSpan;
                  }
                  appendChild(child) { this.children.push(child); return child; }
                  setAttribute(key, value) { this.attrs[key] = value; }
                  addEventListener(type, listener) { this.listeners[type] = listener; }
                  click() { this.listeners.click?.({ preventDefault: () => { this.defaultPrevented = true; } }); }
                  replaceChildren(...children) { this.children = children; }
                  queryText() { return [this.textContent, ...this.children.map(c => c.queryText ? c.queryText() : '')].join(''); }
                }

                const tbody = new Node('tbody');
                const table = new Node('table');
                const calls = [];
                const rowsQueue = [
                  [],
                  [{ botUserId: 42, username: null, displayName: 'No Username', totalExports: 2, totalMessages: 10, totalBytes: 2048, lastSeen: null }],
                  [{ botUserId: 7, username: '@Alice_User', displayName: 'Alice', totalExports: 1, totalMessages: 5, totalBytes: 512, lastSeen: null }],
                ];
                const context = {
                  console,
                  setTimeout,
                  window: {
                    location: { origin: 'https://example.test', href: '' },
                    setTimeout: (callback, delay) => { calls.push({ timeoutDelay: delay }); callback(); },
                    Dashboard: {
                      fetchJson: async (path, params) => { calls.push({ path, params }); return rowsQueue.shift(); },
                      formatNumber: value => String(value),
                      formatBytes: value => `${value} B`,
                      formatDate: value => value || '—',
                      setCountBadge: (name, value) => calls.push({ badge: name, value }),
                      initSortableTable: () => {},
                      readPeriodFromUrl: () => ({ period: 'month' }),
                      onReady: callback => callback(),
                      createElement: (tag, attrs, ...children) => new Node(tag, attrs, children),
                    },
                  },
                  document: {
                    getElementById: id => id === 'users-tbody' ? tbody : (id === 'users-table' ? table : null),
                    createElementNS: (ns, tag) => new Node(tag),
                  },
                };
                context.window.window = context.window;
                context.window.document = context.document;
                vm.createContext(context);
                vm.runInContext(source, context);
                await new Promise(resolve => setTimeout(resolve, 0));
                if (calls[0].path !== '/dashboard/api/stats/users') throw new Error(`unexpected fetch path: ${calls[0].path}`);
                if (tbody.children[0].children[0].colSpan !== 7) throw new Error('empty row colspan must match users table columns');
                if (!tbody.queryText().includes('Нет данных')) throw new Error('empty list is not rendered');
                vm.runInContext(source, context);
                await new Promise(resolve => setTimeout(resolve, 0));
                if (!tbody.queryText().includes('No Username')) throw new Error('user row is not rendered');
                if (!tbody.queryText().includes('—')) throw new Error('missing username fallback is not rendered');
                const idTelegramAction = tbody.children[0].children[2].children[0];
                if (idTelegramAction.attrs.href !== 'tg://user?id=42') throw new Error(`unexpected id href: ${idTelegramAction.attrs.href}`);
                idTelegramAction.click();
                if (context.window.location.href !== 'tg://user?id=42') throw new Error('id fallback did not use tg://user link');

                let closed = false;
                context.window.Telegram = { WebApp: {
                  openTelegramLink: (href, options) => calls.push({ openTelegramLink: href, options }),
                  close: () => { closed = true; },
                }};
                vm.runInContext(source, context);
                await new Promise(resolve => setTimeout(resolve, 0));
                const usernameTelegramAction = tbody.children[0].children[2].children[0];
                if (usernameTelegramAction.attrs.href !== 'https://t.me/Alice_User') throw new Error(`unexpected username href: ${usernameTelegramAction.attrs.href}`);
                usernameTelegramAction.click();
                const openCall = calls.find(call => call.openTelegramLink);
                if (openCall?.openTelegramLink !== 'https://t.me/Alice_User') throw new Error('Telegram WebApp did not receive https://t.me link');
                if (!calls.some(call => call.timeoutDelay === 120)) throw new Error('Mini App close delay was not used');
                if (!closed) throw new Error('Mini App close was not scheduled');
                })().catch(error => { console.error(error); process.exit(1); });
                """.formatted(toJsString(usersSource));

        Path script = Files.createTempFile("users-page-test", ".cjs");
        Files.writeString(script, harness, StandardCharsets.UTF_8);
        ProcessResult result = run("node", script.toString());
        assertThat(result.exitCode())
                .as("node %s%nstdout:%n%s%nstderr:%n%s", script, result.stdout(), result.stderr())
                .isZero();
    }

    private static String toJsString(String value) {
        return "`" + value.replace("\\", "\\\\").replace("`", "\\`").replace("${", "\\${") + "`";
    }

    private static boolean isRepositoryFile(Path path) {
        String normalized = path.normalize().toString();
        return !normalized.startsWith(".git/")
                && !normalized.startsWith("target/");
    }

    private static boolean isTextSourceFile(Path path) {
        String file = path.getFileName().toString();
        return file.equals("Dockerfile")
                || file.equals("AGENTS.md")
                || file.endsWith(".java")
                || file.endsWith(".js")
                || file.endsWith(".html")
                || file.endsWith(".css")
                || file.endsWith(".xml")
                || file.endsWith(".properties")
                || file.endsWith(".yml")
                || file.endsWith(".yaml")
                || file.endsWith(".md")
                || file.endsWith(".txt")
                || file.endsWith(".py")
                || file.endsWith(".sql");
    }

    private static ProcessResult run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.waitFor(), stdout, stderr);
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
