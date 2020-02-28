package dk.xam.jbang;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import org.hamcrest.collection.IsCollectionWithSize;
import org.junit.Rule;
import org.junit.contrib.java.lang.system.EnvironmentVariables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

public class TestMain {

	public static final String EXAMPLES_FOLDER = "examples";
	static File examplesTestFolder;

	@BeforeAll
	static void init() throws URISyntaxException {
		URL examplesUrl = TestMain.class.getClassLoader().getResource(EXAMPLES_FOLDER);
		examplesTestFolder = new File(new File(examplesUrl.toURI()).getAbsolutePath());
	}

	@Test
	void testHelloWorld() throws IOException {

		Main main = new Main();
		String arg = new File(examplesTestFolder, "helloworld.java").getAbsolutePath();
		new CommandLine(main).parseArgs(arg);

		String result = main.generateCommandLine(new Script(new File("helloworld.java"), ""));

		assertThat(result, startsWith("java"));
		assertThat(result, containsString("helloworld.java"));
		// assertThat(result, containsString("--source 11"));
	}

	@Rule
	public final EnvironmentVariables environmentVariables = new EnvironmentVariables();

	@Test
	void testHelloWorldShell() throws IOException {

		environmentVariables.clear("JAVA_HOME");

		Main main = new Main();
		String arg = new File(examplesTestFolder, "helloworld.jsh").getAbsolutePath();
		new CommandLine(main).parseArgs(arg, "blah");

		String result = main.generateCommandLine(new Script(new File("helloworld.jsh"), ""));

		assertThat(result, startsWith("jshell"));
		assertThat(result, not(containsString("  ")));
		assertThat(result, containsString("helloworld.jsh"));
		assertThat(result, not(containsString("--source 11")));
		assertThat(result, containsString("--startup=DEFAULT --startup="));
		assertThat(result, not(containsString("blah")));

	}

	@Test
	void testDebug() throws IOException {

		environmentVariables.clear("JAVA_HOME");

		Main main = new Main();
		String arg = new File(examplesTestFolder, "helloworld.java").getAbsolutePath();
		new CommandLine(main).parseArgs("--debug", arg);

		String result = main.generateCommandLine(new Script(new File("helloworld.java"), ""));

		assertThat(result, startsWith("java "));
		assertThat(result, containsString("helloworld.java"));
//		assertThat(result, containsString(" --source 11 "));
		assertThat(result, containsString("jdwp"));
		assertThat(result, not(containsString("  ")));
		assertThat(result, not(containsString("classpath")));
	}

	@Test
	void testDependencies() throws IOException {

		Main main = new Main();
		String arg = new File(examplesTestFolder, "classpath_example.java").getAbsolutePath();
		new CommandLine(main).parseArgs(arg);

		String result = main.generateCommandLine(new Script(new File(arg)));

		assertThat(result, startsWith("java "));
		assertThat(result, containsString("classpath_example.java"));
//		assertThat(result, containsString(" --source 11 "));
		assertThat(result, not(containsString("  ")));
		assertThat(result, containsString("classpath"));
		assertThat(result, containsString("log4j"));
	}

	@Test
	void testURLPrepare() throws IOException {

		String url = new File(examplesTestFolder, "classpath_example.java").toURI().toString();

		Script result = Main.prepareScript(url);

		assertThat(result.toString(), not(containsString(url)));

		assertThat(Util.readString(result.backingFile.toPath()),
				containsString("Logger.getLogger(classpath_example.class);"));

		Main main = new Main();
		new CommandLine(main).parseArgs(url);

		String s = main.generateCommandLine(Main.prepareScript(url));

		assertThat(s, not(containsString("file:")));
	}

	@Test
	void testURLPrepareDoesNotExist() throws IOException {

		String url = new File(examplesTestFolder, "classpath_example.java.dontexist").toURI().toString();

		assertThrows(ExitException.class, () -> Main.prepareScript(url));
	}

	@Test
	void testFindMain(@TempDir Path dir) throws IOException {

		File basedir = dir.resolve("a/b/c").toFile();
		boolean mkdirs = basedir.mkdirs();
		assert (mkdirs);
		File classfile = new File(basedir, "mymain.class");
		classfile.setLastModified(System.currentTimeMillis());
		classfile.createNewFile();
		assert (classfile.exists());

		assertEquals(Main.findMainClass(dir, classfile.toPath()), "a.b.c.mymain");

	}

	@Test
	void testCreateJar(@TempDir Path rootdir) throws IOException {

		File dir = new File(rootdir.toFile(), "content");

		File basedir = dir.toPath().resolve("a/b/c").toFile();
		boolean mkdirs = basedir.mkdirs();
		assert (mkdirs);
		File classfile = new File(basedir, "mymain.class");
		classfile.setLastModified(System.currentTimeMillis());
		classfile.createNewFile();
		assert (classfile.exists());

		File out = new File(rootdir.toFile(), "content.jar");

		Main.createJarFile(dir, out, "wonkabear");

		try (JarFile jf = new JarFile(out)) {

			assertThat(Collections.list(jf.entries()), IsCollectionWithSize.hasSize(5));

			assertThat(jf.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS), equalTo("wonkabear"));

			assert (out.exists());
		}

	}

	@Test
	void testGenArgs() {

		assertThat(new Main().generateArgs(Collections.emptyList()), equalTo("String[] args = {  }"));

		assertThat(new Main().generateArgs(Arrays.asList("one")), equalTo("String[] args = { \"one\" }"));

		assertThat(new Main().generateArgs(Arrays.asList("one", "two")),
				equalTo("String[] args = { \"one\", \"two\" }"));

		assertThat(new Main().generateArgs(Arrays.asList("one", "two", "three \"quotes\"")),
				equalTo("String[] args = { \"one\", \"two\", \"three \\\"quotes\\\"\" }"));

	}

	@Test
	void testDualClasses(@TempDir File output) throws IOException {

		String base = "//usr/bin/env jbang \"$0\" \"$@\" ; exit $?\n" +
				"// //DEPS <dependency1> <dependency2>\n" +
				"\n" +
				"import static java.lang.System.*;\n" +
				"\n" +
				"class firstclass {\n" +
				"\n" +
				"}\n" +
				"\n" +
				"public class dualclass {\n" +
				"\n" +
				"    public static void main(String... args) {\n" +
				"        out.println(\"Hello \" + (args.length>0?args[0]:\"World\"));\n" +
				"    }\n" +
				"}\n";

		File f = new File(output, "dualclass.java");

		Util.writeString(f.toPath(), base);

		Main m = new Main();

		Script script = new Script(f);
		Main.build(script, m);

		assertThat(script.getMainClass(), equalTo("dualclass"));
	}

	@Test
	void testFetchFromGitLab(@TempDir Path dir) throws IOException {

		Path x = Util.downloadFile("https://gitlab.com/maxandersen/jbang-gitlab/-/raw/master/helloworld.java",
				dir.toFile());
		assertEquals(x.getFileName().toString(), "helloworld.java");
	}

	@Test
	void testFetchFromGist(@TempDir Path dir) throws IOException {

		Path x = Util.downloadFile("https://gist.github.com/maxandersen/590b8a0e824faeb3ee7ddfad741ce842/raw",
				dir.toFile());
		assertEquals(x.getFileName().toString(), "raw");
	}

	@Test
	void testSwizzle(@TempDir Path dir) throws IOException {

		assertThat(
				Main.swizzleURL("https://github.com/maxandersen/jbang/blob/master/examples/helloworld.java"),
				equalTo("https://raw.githubusercontent.com/maxandersen/jbang/master/examples/helloworld.java"));

		assertThat(
				Main.swizzleURL("https://gitlab.com/maxandersen/jbang-gitlab/-/blob/master/helloworld.java"),
				equalTo("https://gitlab.com/maxandersen/jbang-gitlab/-/raw/master/helloworld.java"));

		assertThat(
				Main.swizzleURL("https://bitbucket.org/Shoeboom/test/src/master/helloworld.java"),
				equalTo("https://bitbucket.org/Shoeboom/test/raw/master/helloworld.java"));

	}
}
