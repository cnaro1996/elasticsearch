/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.file;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.elasticsearch.xpack.XPackPlugin;
import org.elasticsearch.xpack.security.audit.logfile.CapturingLogger;
import org.elasticsearch.xpack.security.authc.AuthenticationResult;
import org.elasticsearch.xpack.security.authc.RealmConfig;
import org.elasticsearch.xpack.security.authc.support.Hasher;
import org.elasticsearch.xpack.security.user.User;
import org.junit.After;
import org.junit.Before;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class FileUserPasswdStoreTests extends ESTestCase {

    private Settings settings;
    private Environment env;
    private ThreadPool threadPool;

    @Before
    public void init() {
        settings = Settings.builder()
                .put("resource.reload.interval.high", "2s")
                .put("path.home", createTempDir())
                .build();
        env = new Environment(settings);
        threadPool = new TestThreadPool("test");
    }

    @After
    public void shutdown() throws InterruptedException {
        terminate(threadPool);
    }

    public void testStore_ConfiguredWithUnreadableFile() throws Exception {
        Path xpackConf = env.configFile().resolve(XPackPlugin.NAME);
        Files.createDirectories(xpackConf);
        Path file = xpackConf.resolve("users");

        // writing in utf_16 should cause a parsing error as we try to read the file in utf_8
        Files.write(file, Collections.singletonList("aldlfkjldjdflkjd"), StandardCharsets.UTF_16);

        Settings fileSettings = randomBoolean() ? Settings.EMPTY : Settings.builder().put("files.users", file.toAbsolutePath()).build();
        RealmConfig config = new RealmConfig("file-test", fileSettings, settings, env, threadPool.getThreadContext());
        ResourceWatcherService watcherService = new ResourceWatcherService(settings, threadPool);
        FileUserPasswdStore store = new FileUserPasswdStore(config, watcherService);
        assertThat(store.usersCount(), is(0));
    }

    public void testStore_AutoReload() throws Exception {
        Path users = getDataPath("users");
        Path xpackConf = env.configFile().resolve(XPackPlugin.NAME);
        Files.createDirectories(xpackConf);
        Path file = xpackConf.resolve("users");
        Files.copy(users, file, StandardCopyOption.REPLACE_EXISTING);

        Settings fileSettings = randomBoolean() ? Settings.EMPTY : Settings.builder().put("files.users", file.toAbsolutePath()).build();
        RealmConfig config = new RealmConfig("file-test", fileSettings, settings, env, threadPool.getThreadContext());
        ResourceWatcherService watcherService = new ResourceWatcherService(settings, threadPool);
        final CountDownLatch latch = new CountDownLatch(1);

        FileUserPasswdStore store = new FileUserPasswdStore(config, watcherService, latch::countDown);

        User user = new User("bcrypt");
        assertThat(store.userExists("bcrypt"), is(true));
        AuthenticationResult result = store.verifyPassword("bcrypt", new SecureString("test123"), () -> user);
        assertThat(result.getStatus(), is(AuthenticationResult.Status.SUCCESS));
        assertThat(result.getUser(), is(user));

        watcherService.start();

        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            writer.newLine();
            writer.append("foobar:").append(new String(Hasher.BCRYPT.hash(new SecureString("barfoo"))));
        }

        if (!latch.await(5, TimeUnit.SECONDS)) {
            fail("Waited too long for the updated file to be picked up");
        }

        assertThat(store.userExists("foobar"), is(true));
        result = store.verifyPassword("foobar", new SecureString("barfoo"), () -> user);
        assertThat(result.getStatus(), is(AuthenticationResult.Status.SUCCESS));
        assertThat(result.getUser(), is(user));
    }

    public void testStore_AutoReload_WithParseFailures() throws Exception {
        Path users = getDataPath("users");
        Path xpackConf = env.configFile().resolve(XPackPlugin.NAME);
        Files.createDirectories(xpackConf);
        Path testUsers = xpackConf.resolve("users");
        Files.copy(users, testUsers, StandardCopyOption.REPLACE_EXISTING);

        Settings fileSettings = Settings.builder()
                .put("files.users", testUsers.toAbsolutePath())
                .build();

        RealmConfig config = new RealmConfig("file-test", fileSettings, settings, env, threadPool.getThreadContext());
        ResourceWatcherService watcherService = new ResourceWatcherService(settings, threadPool);
        final CountDownLatch latch = new CountDownLatch(1);

        FileUserPasswdStore store = new FileUserPasswdStore(config, watcherService, latch::countDown);

        User user = new User("bcrypt");
        final AuthenticationResult result = store.verifyPassword("bcrypt", new SecureString("test123"), () -> user);
        assertThat(result.getStatus(), is(AuthenticationResult.Status.SUCCESS));
        assertThat(result.getUser(), is(user));

        watcherService.start();

        // now replacing the content of the users file with something that cannot be read
        Files.write(testUsers, Collections.singletonList("aldlfkjldjdflkjd"), StandardCharsets.UTF_16);

        if (!latch.await(5, TimeUnit.SECONDS)) {
            fail("Waited too long for the updated file to be picked up");
        }

        assertThat(store.usersCount(), is(0));
    }

    public void testParseFile() throws Exception {
        Path path = getDataPath("users");
        Map<String, char[]> users = FileUserPasswdStore.parseFile(path, null, Settings.EMPTY);
        assertThat(users, notNullValue());
        assertThat(users.size(), is(6));
        assertThat(users.get("bcrypt"), notNullValue());
        assertThat(new String(users.get("bcrypt")), equalTo("$2a$05$zxnP0vdREMxnEpkLCDI2OuSaSk/QEKA2.A42iOpI6U2u.RLLOWm1e"));
        assertThat(users.get("bcrypt10"), notNullValue());
        assertThat(new String(users.get("bcrypt10")), equalTo("$2y$10$FMhmFjwU5.qxQ/BsEciS9OqcJVkFMgXMo4uH5CelOR1j4N9zIv67e"));
        assertThat(users.get("md5"), notNullValue());
        assertThat(new String(users.get("md5")), equalTo("$apr1$R3DdqiAZ$aljIkaIVPSarmDMlJUBBP."));
        assertThat(users.get("crypt"), notNullValue());
        assertThat(new String(users.get("crypt")), equalTo("hsP1PYSLsEEvs"));
        assertThat(users.get("plain"), notNullValue());
        assertThat(new String(users.get("plain")), equalTo("{plain}test123"));
        assertThat(users.get("sha"), notNullValue());
        assertThat(new String(users.get("sha")), equalTo("{SHA}cojt0Pw//L6ToM8G41aOKFIWh7w="));
    }

    public void testParseFile_Empty() throws Exception {
        Path empty = createTempFile();
        Logger logger = CapturingLogger.newCapturingLogger(Level.DEBUG);
        Map<String, char[]> users = FileUserPasswdStore.parseFile(empty, logger, Settings.EMPTY);
        assertThat(users.isEmpty(), is(true));
        List<String> events = CapturingLogger.output(logger.getName(), Level.DEBUG);
        assertThat(events.size(), is(1));
        assertThat(events.get(0), containsString("parsed [0] users"));
    }

    public void testParseFile_WhenFileDoesNotExist() throws Exception {
        Path file = createTempDir().resolve(randomAlphaOfLength(10));
        Logger logger = CapturingLogger.newCapturingLogger(Level.INFO);
        Map<String, char[]> users = FileUserPasswdStore.parseFile(file, logger, Settings.EMPTY);
        assertThat(users, notNullValue());
        assertThat(users.isEmpty(), is(true));
    }

    public void testParseFile_WhenCannotReadFile() throws Exception {
        Path file = createTempFile();
        // writing in utf_16 should cause a parsing error as we try to read the file in utf_8
        Files.write(file, Collections.singletonList("aldlfkjldjdflkjd"), StandardCharsets.UTF_16);
        Logger logger = CapturingLogger.newCapturingLogger(Level.INFO);
        try {
            FileUserPasswdStore.parseFile(file, logger, Settings.EMPTY);
            fail("expected a parse failure");
        } catch (IllegalStateException se) {
            this.logger.info("expected", se);
        }
    }

    public void testParseFile_InvalidLineDoesNotResultInLoggerNPE() throws Exception {
        Path file = createTempFile();
        Files.write(file, Arrays.asList("NotValidUsername=Password", "user:pass"), StandardCharsets.UTF_8);
        Map<String, char[]> users = FileUserPasswdStore.parseFile(file, null, Settings.EMPTY);
        assertThat(users, notNullValue());
        assertThat(users.keySet(), hasSize(1));
    }

    public void testParseFileLenient_WhenCannotReadFile() throws Exception {
        Path file = createTempFile();
        // writing in utf_16 should cause a parsing error as we try to read the file in utf_8
        Files.write(file, Collections.singletonList("aldlfkjldjdflkjd"), StandardCharsets.UTF_16);
        Logger logger = CapturingLogger.newCapturingLogger(Level.INFO);
        Map<String, char[]> users = FileUserPasswdStore.parseFileLenient(file, logger, Settings.EMPTY);
        assertThat(users, notNullValue());
        assertThat(users.isEmpty(), is(true));
        List<String> events = CapturingLogger.output(logger.getName(), Level.ERROR);
        assertThat(events.size(), is(1));
        assertThat(events.get(0), containsString("failed to parse users file"));
    }

    public void testParseFileWithLineWithEmptyPasswordAndWhitespace() throws Exception {
        Path file = createTempFile();
        Files.write(file, Collections.singletonList("user: "), StandardCharsets.UTF_8);
        Map<String, char[]> users = FileUserPasswdStore.parseFile(file, null, Settings.EMPTY);
        assertThat(users, notNullValue());
        assertThat(users.keySet(), is(empty()));
    }

}
