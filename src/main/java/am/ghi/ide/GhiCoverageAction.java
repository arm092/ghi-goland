package am.ghi.ide;

import com.intellij.execution.RunContentExecutor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.*;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class GhiCoverageAction extends DumbAwareAction {
    @Override public ActionUpdateThread getActionUpdateThread(){return ActionUpdateThread.BGT;}
    @Override public void update(AnActionEvent event){event.getPresentation().setEnabled(event.getProject()!=null);}
    static GeneralCommandLine command(String compiler,Path root,Path profile){return new GeneralCommandLine(compiler,"test","--coverprofile",profile.toString(),root.toString()).withWorkDirectory(root.toFile()).withCharset(java.nio.charset.StandardCharsets.UTF_8);}
    void showConsole(com.intellij.openapi.project.Project project,ProcessHandler handler,Path root){
        new RunContentExecutor(project,handler).withTitle("Ghi Test with Coverage").withFilter(new GhiConsoleFilter(project,root)).run();
    }
    @Override public void actionPerformed(AnActionEvent event){
        var project=event.getProject();if(project==null)return;
        FileDocumentManager.getInstance().saveAllDocuments();var settings=GhiSettings.get(project).getState();final Path root;
        try{root=GhiCommand.directory(project.getBasePath(),settings.directory);if(!Files.isDirectory(root))throw new IllegalArgumentException("Ghi project directory does not exist.");}
        catch(RuntimeException error){Messages.showErrorDialog(project,error.getMessage(),"Ghi Coverage");return;}
        var coverage=GhiCoverageService.get(project);long token=coverage.begin(root);
        ProgressManager.getInstance().run(new Task.Backgroundable(project,"Ghi test with coverage",false){
            @Override public void run(ProgressIndicator indicator){
                Path temporary=null;
                try{
                    var snapshot=GhiCoverageProfile.snapshot(root);
                    Path cache=Path.of(PathManager.getSystemPath(),"ghi-coverage");Files.createDirectories(cache);
                    temporary=Files.createTempDirectory(cache,"run-");Path profile=temporary.resolve("statements.out");
                    var cancelled=new AtomicBoolean();
                    var handler=new KillableColoredProcessHandler(command(GhiCommand.executable(settings.executable),root,profile)){
                        @Override protected void destroyProcessImpl(){cancelled.set(true);super.destroyProcessImpl();}
                    };
                    Path runDirectory=temporary;
                    handler.addProcessListener(new ProcessListener(){
                        @Override public void processTerminated(ProcessEvent event){
                            ApplicationManager.getApplication().executeOnPooledThread(()->{
                                GhiCoverageProfile result=null;String error=null;
                                try{
                                    if(cancelled.get())error="Coverage run cancelled. No new results.";
                                    else if(event.getExitCode()!=0)error="Tests failed. No new coverage results. See the Ghi test console.";
                                    else{result=GhiCoverageProfile.read(profile,snapshot);if(!result.unchanged()){result=null;error="Sources changed during the run. Run tests with coverage again.";}}
                                }catch(Exception failure){error="Coverage unavailable: "+failure.getMessage();}
                                finally{try{Files.deleteIfExists(profile);Files.deleteIfExists(runDirectory);}catch(java.io.IOException ignored){}}
                                GhiCoverageProfile completed=result;String message=error;
                                ApplicationManager.getApplication().invokeLater(()->{if(!project.isDisposed())coverage.finish(token,completed,message);});
                            });
                        }
                    });
                    ProcessTerminatedListener.attach(handler,project);
                    ApplicationManager.getApplication().invokeLater(()->{
                        if(project.isDisposed()){handler.startNotify();handler.destroyProcess();return;}
                        showConsole(project,handler,root);
                    });
                }catch(Exception failure){
                    if(temporary!=null)try{Files.deleteIfExists(temporary.resolve("statements.out"));Files.deleteIfExists(temporary);}catch(java.io.IOException ignored){}
                    ApplicationManager.getApplication().invokeLater(()->{if(!project.isDisposed())coverage.finish(token,null,"Coverage unavailable: "+failure.getMessage());});
                }
            }
        });
    }
}
