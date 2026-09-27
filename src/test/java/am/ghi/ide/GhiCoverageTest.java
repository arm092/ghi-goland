package am.ghi.ide;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.actionSystem.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Coverage contracts: UTF-8 locations, statement totals, editor state and real compiler runs. */
public class GhiCoverageTest extends BasePlatformTestCase {
    private Path root;
    private String contentUrl;
    @Override protected void setUp() throws Exception {super.setUp();root=Files.createTempDirectory("ghi-coverage-test-");}
    @Override protected void tearDown() throws Exception {
        try{
            GhiCoverageService.get(getProject()).clear("Coverage cleared.");
            WriteAction.run(()->{
                var model=com.intellij.openapi.roots.ModuleRootManager.getInstance(myFixture.getModule()).getModifiableModel();
                for(var entry:model.getContentEntries())if(entry.getUrl().equals(contentUrl))model.removeContentEntry(entry);
                model.commit();
            });
            var manager=com.intellij.execution.ui.RunContentManager.getInstance(getProject());
            for(var descriptor:manager.getAllDescriptors()){
                var handler=descriptor.getProcessHandler();if(handler!=null&&!handler.isProcessTerminated()){handler.destroyProcess();handler.waitFor(10000);}
                manager.removeRunContent(com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance(),descriptor);
                if(!com.intellij.openapi.util.Disposer.isDisposed(descriptor))com.intellij.openapi.util.Disposer.dispose(descriptor);
            }
            if(root!=null)try(var files=Files.walk(root)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
        }finally{super.tearDown();}
    }
    public void testStatementCountsAndUtf8ColumnsRejectInvalidProfiles() throws Exception {
        Path file=root.resolve("source paths/նշում.ghi");String source="namespace app\nfunc choose(){ label := \"😀հայ\"; println(label); return }\n";
        var snapshot=new GhiCoverageProfile.Snapshot(root,Map.of(file,source));
        int offset=source.indexOf("println"),lineStart=source.indexOf('\n')+1;
        int byteColumn=source.substring(lineStart,offset).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+1;
        String row="source paths/նշում.ghi:2."+byteColumn+",2."+(byteColumn+1)+" 1 0\n";
        var profile=GhiCoverageProfile.parse("mode: set\nsource paths/նշում.ghi:2.16,2.17 1 1\n"+row,snapshot);
        var data=profile.files.getFirst();assertEquals("50.0%",data.percentage());assertEquals(2,data.statements().size());assertEquals(1,data.covered());
        assertEquals(offset,data.statements().getLast().offset());
        for(String invalid:List.of("mode: count\n"+row,"mode: set\n../outside.ghi:2.1,2.2 1 1\n","mode: set\nC:/outside.ghi:2.1,2.2 1 1\n","mode: set\n"+row+row,
            "mode: set\nsource paths/նշում.ghi:999.1,999.2 1 1\n","mode: set\n"+row.replace("1 0","2 0"),"mode: set\n"+row.replace("1 0","1 3"))){
            try{GhiCoverageProfile.parse(invalid,snapshot);fail("Accepted invalid profile: "+invalid);}catch(java.io.IOException expected){}
        }
        int emoji=source.indexOf("😀"),inside=source.substring(lineStart,emoji).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+2;
        try{GhiCoverageProfile.parse("mode: set\nsource paths/նշում.ghi:2."+inside+",2."+(inside+1)+" 1 1\n",snapshot);fail("Accepted a split UTF-8 character");}catch(java.io.IOException expected){}
        assertEquals("n/a",GhiCoverageProfile.parse("mode: set\n",snapshot).files.getFirst().percentage());
    }
    public void testExcludedSourcesAndChangedSnapshot() throws Exception {
        Files.writeString(root.resolve("main.ghi"),"namespace main\nfunc main(){println(1)}\n");
        for(String name:List.of("tests",".ghi/packages/lib","vendor","nested"))Files.createDirectories(root.resolve(name));
        for(String name:List.of("tests/test.ghi",".ghi/packages/lib/code.ghi","vendor/code.ghi","nested/code.ghi"))Files.writeString(root.resolve(name),"namespace lib\n");
        Files.writeString(root.resolve("nested/mojave.json"),"{}");
        var snapshot=GhiCoverageProfile.snapshot(root);assertEquals(Set.of(root.resolve("main.ghi")),snapshot.sources().keySet());
        var profile=GhiCoverageProfile.parse("mode: set\nmain.ghi:2.13,2.14 1 1\n",snapshot);assertTrue(profile.unchanged());
        Files.writeString(root.resolve("added.ghi"),"namespace main\nfunc other(){println(2)}\n");assertFalse(profile.unchanged());
        try{GhiCoverageProfile.parse("mode: set\ntests/test.ghi:1.1,1.2 1 1\n",snapshot);fail("Included tests");}catch(java.io.IOException expected){}
    }
    public void testEditorAnnotationsAndRunGenerations() throws Exception {
        var file=myFixture.configureByText("main.ghi","namespace main\nfunc main(){println(1);println(2)}\n");
        var path=Path.of(file.getVirtualFile().getPath());String source=file.getText();
        var snapshot=new GhiCoverageProfile.Snapshot(path.getParent(),Map.of(path,source));
        var data=GhiCoverageProfile.parse("mode: set\nmain.ghi:2.13,2.14 1 1\nmain.ghi:2.24,2.25 1 0\n",snapshot);
        var coverage=GhiCoverageService.get(getProject());long first=coverage.begin(path.getParent());coverage.finish(first,data,null);
        assertEquals("50.0%",coverage.files().getFirst().percentage());
        var markers=Arrays.stream(myFixture.getEditor().getMarkupModel().getAllHighlighters()).filter(marker->marker.getErrorStripeTooltip()!=null&&marker.getErrorStripeTooltip().toString().startsWith("Ghi")).toList();
        assertEquals(3,markers.size());assertTrue(markers.stream().anyMatch(marker->"Ghi statement not executed".equals(marker.getErrorStripeTooltip())));
        long second=coverage.begin(path.getParent());assertTrue(coverage.files().isEmpty());
        coverage.finish(first,data,null);assertTrue("A superseded run published results",coverage.files().isEmpty());
        coverage.finish(second,null,"Tests failed. No new coverage results.");assertTrue(coverage.files().isEmpty());
        long third=coverage.begin(path.getParent());coverage.finish(third,data,null);
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(),()->myFixture.getEditor().getDocument().insertString(0,"// edited\n"));assertTrue("Edited source retained coverage",coverage.files().isEmpty());
        assertFalse(Arrays.stream(myFixture.getEditor().getMarkupModel().getAllHighlighters()).anyMatch(marker->marker.getErrorStripeTooltip()!=null&&marker.getErrorStripeTooltip().toString().startsWith("Ghi")));
        coverage.finish(third,data,null);assertTrue("A run completed after an edit",coverage.files().isEmpty());
    }
    public void testRealCompilerCoverageActionAndFailedRun() throws Exception {
        String compiler=System.getenv("GHI_TEST_COMPILER");if(compiler==null)return;
        String source="namespace app\nfunc Choose(value bool) int { if value { return 1 }; return 2 }\nfunc unused() int { return 3 }\n";
        Files.writeString(root.resolve("main.ghi"),source);Files.createDirectories(root.resolve("tests"));
        String test="namespace tests\nimport testing \"go:testing\"\nimport app\nfunc TestChoose(t *testing.T){if app.Choose(true)!=1 {t.Fatal(\"wrong\")}}\n";
        Files.writeString(root.resolve("tests/main.ghi"),test);
        var virtual=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root);assertNotNull(virtual);virtual.refresh(false,true);
        contentUrl=virtual.getUrl();
        WriteAction.run(()->{var model=com.intellij.openapi.roots.ModuleRootManager.getInstance(myFixture.getModule()).getModifiableModel();model.addContentEntry(virtual.getUrl());model.commit();});
        myFixture.configureFromExistingVirtualFile(virtual.findChild("main.ghi"));
        var windows=com.intellij.openapi.wm.ToolWindowManager.getInstance(getProject());
        var coverageWindow=windows.getToolWindow("Ghi Coverage");
        if(coverageWindow==null)coverageWindow=windows.registerToolWindow("Ghi Coverage",true,com.intellij.openapi.wm.ToolWindowAnchor.RIGHT);
        new GhiCoverageWindow().createToolWindowContent(getProject(),coverageWindow);
        var settings=GhiSettings.get(getProject()).getState();settings.executable=compiler;settings.directory=root.toString();
        assertNotNull(ActionManager.getInstance().getAction("Ghi.TestWithCoverage"));
        var handlers=new ArrayList<com.intellij.execution.process.ProcessHandler>();
        var action=new GhiCoverageAction(){
            @Override void showConsole(com.intellij.openapi.project.Project project,com.intellij.execution.process.ProcessHandler handler,Path directory){
                var console=com.intellij.execution.filters.TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
                com.intellij.openapi.util.Disposer.register(project,console);console.attachToProcess(handler);handlers.add(handler);handler.startNotify();
            }
        };
        var coverage=GhiCoverageService.get(getProject());run(action);waitFor(()->!coverage.status().startsWith("Running"));
        assertFalse(coverage.status(),coverage.files().isEmpty());assertEquals(1,coverage.files().size());
        var data=coverage.files().getFirst();assertTrue(data.covered()>0);assertTrue(data.covered()<data.statements().size());
        assertTrue(data.statements().stream().anyMatch(statement->statement.line()==1&&statement.covered()));
        assertTrue(data.statements().stream().anyMatch(statement->statement.line()==1&&!statement.covered()));
        assertTrue(data.statements().stream().anyMatch(statement->statement.line()==2&&!statement.covered()));
        assertEquals(String.format(Locale.ROOT,"%.1f%%",100.0*data.covered()/data.statements().size()),data.percentage());
        var window=com.intellij.openapi.wm.ToolWindowManager.getInstance(getProject()).getToolWindow("Ghi Coverage");assertNotNull(window);
        Files.writeString(root.resolve("tests/main.ghi"),test.replace("app.Choose(true)!=1","app.Choose(true)!=2"));
        run(action);assertTrue(coverage.files().isEmpty());waitFor(()->!coverage.status().startsWith("Running"));
        assertTrue(coverage.status(),coverage.status().startsWith("Tests failed"));assertTrue(coverage.files().isEmpty());
        // Stop a real sleeping test: it must not publish a profile from the previous successful run.
        Files.writeString(root.resolve("tests/main.ghi"),"namespace tests\nimport testing \"go:testing\"\nimport time \"go:time\"\nfunc TestWait(t *testing.T){time.Sleep(30 * time.Second)}\n");
        run(action);waitFor(()->handlers.stream().anyMatch(handler->!handler.isProcessTerminated()));
        for(var handler:handlers)if(!handler.isProcessTerminated())handler.destroyProcess();
        waitFor(()->!coverage.status().startsWith("Running"));assertTrue(coverage.status(),coverage.status().startsWith("Coverage run cancelled"));assertTrue(coverage.files().isEmpty());
    }
    private void run(com.intellij.openapi.actionSystem.AnAction action){action.actionPerformed(AnActionEvent.createFromAnAction(action,null,ActionPlaces.UNKNOWN,com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(getProject())));}
    private void waitFor(java.util.function.BooleanSupplier condition) throws Exception {long deadline=System.currentTimeMillis()+TimeUnit.SECONDS.toMillis(60);while(!condition.getAsBoolean()&&System.currentTimeMillis()<deadline){com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();Thread.sleep(25);}assertTrue("Coverage action timed out",condition.getAsBoolean());}
}
