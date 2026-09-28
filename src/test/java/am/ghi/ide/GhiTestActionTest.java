package am.ghi.ide;

import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Command boundaries and the native compiler's benchmark/race behavior. */
public class GhiTestActionTest extends BasePlatformTestCase {
    private Path root;
    @Override protected void setUp() throws Exception{super.setUp();root=Files.createTempDirectory("ghi-test-options-");}
    @Override protected void tearDown() throws Exception{try{if(root!=null)try(var files=Files.walk(root)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}finally{super.tearDown();}}
    public void testActionsAndValidatedArguments(){
        assertNotNull(ActionManager.getInstance().getAction("Ghi.TestWithRace"));
        assertNotNull(ActionManager.getInstance().getAction("Ghi.RunBenchmarks"));
        var settings=new GhiSettings.State();settings.benchmarkPattern="Choose";settings.benchmarkTime="2x";settings.benchmarkCount="2";
        var bench=GhiTestCommand.benchmark("ghi",root,settings).getCommandLineList(null);
        assertEquals(List.of("ghi","test","--bench","Choose","--benchtime","2x","--count","2","--benchmem",root.toString()),bench);
        assertEquals(List.of("ghi","test","--race",root.toString()),GhiTestCommand.race("ghi",root).getCommandLineList(null));
        settings.benchmarkMemory=false;assertFalse(GhiTestCommand.benchmark("ghi",root,settings).getCommandLineList(null).contains("--benchmem"));
        for(String value:List.of("","0","-1","999999999999999999999","1.5")){
            settings.benchmarkCount=value;try{GhiTestCommand.benchmark("ghi",root,settings);fail("Accepted count: "+value);}catch(IllegalArgumentException expected){}
        }
        settings.benchmarkCount="1";
        for(String value:List.of("0x","-1s","1day","0s")){
            settings.benchmarkTime=value;try{GhiTestCommand.benchmark("ghi",root,settings);fail("Accepted time: "+value);}catch(IllegalArgumentException expected){}
        }
        settings.benchmarkTime="1s";settings.benchmarkPattern="[";
        try{GhiTestCommand.benchmark("ghi",root,settings);fail("Accepted invalid regex");}catch(IllegalArgumentException expected){}
    }
    public void testRealBenchmarksRaceFailureAndCancellation() throws Exception{
        String compiler=System.getenv("GHI_TEST_COMPILER");if(compiler==null||compiler.isBlank())return;
        Files.writeString(root.resolve("main.ghi"),"namespace app\nfunc Choose() int { return 1 }\n");Files.createDirectory(root.resolve("tests"));
        Files.writeString(root.resolve("tests/main.ghi"),"namespace tests\nimport testing \"go:testing\"\nimport app\nfunc TestChoose(t *testing.T){if app.Choose()!=1 {t.Fatal(\"wrong\")}}\nfunc BenchmarkChoose(b *testing.B){for i := 0; i < b.N; i++ {app.Choose()}}\n");
        var settings=new GhiSettings.State();settings.benchmarkPattern="Choose";settings.benchmarkTime="2x";settings.benchmarkCount="2";
        var bench=GhiTestCommand.benchmark(compiler,root,settings).createProcess();
        try{assertTrue("Benchmark timed out",bench.waitFor(60,TimeUnit.SECONDS));String output=new String(bench.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(output,0,bench.exitValue());assertEquals(output,2,output.split("BenchmarkChoose-",-1).length-1);assertTrue(output,output.contains("B/op"));}finally{bench.destroyForcibly();}
        var race=GhiTestCommand.race(compiler,root).withEnvironment("CGO_ENABLED","0").withRedirectErrorStream(true).createProcess();
        try{assertTrue("Race command timed out",race.waitFor(60,TimeUnit.SECONDS));String output=new String(race.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue("Expected local toolchain failure: "+output,race.exitValue()!=0&&output.contains("race requires cgo"));}finally{race.destroyForcibly();}
        Files.writeString(root.resolve("tests/main.ghi"),"namespace tests\nimport testing \"go:testing\"\nimport time \"go:time\"\nfunc BenchmarkWait(b *testing.B){time.Sleep(30 * time.Second)}\n");
        settings.benchmarkPattern="Wait";settings.benchmarkTime="1x";settings.benchmarkCount="1";
        var handler=new KillableColoredProcessHandler(GhiTestCommand.benchmark(compiler,root,settings));handler.startNotify();
        try{assertFalse("Benchmark exited before stop",handler.waitFor(1000));handler.destroyProcess();assertTrue("Benchmark did not stop",handler.waitFor(15000));}
        finally{if(!handler.isProcessTerminated()){handler.destroyProcess();handler.waitFor(15000);}}
    }
}
