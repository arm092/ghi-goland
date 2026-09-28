package am.ghi.ide;

import com.intellij.execution.RunContentExecutor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import java.nio.file.*;

/** One-shot native test commands with the same stoppable console as other Ghi actions. */
public abstract class GhiTestAction extends DumbAwareAction {
    private final String title;
    protected GhiTestAction(String title){this.title=title;}
    abstract GeneralCommandLine command(String compiler,Path root,GhiSettings.State settings);
    @Override public ActionUpdateThread getActionUpdateThread(){return ActionUpdateThread.BGT;}
    @Override public void update(AnActionEvent event){event.getPresentation().setEnabled(event.getProject()!=null);}
    @Override public void actionPerformed(AnActionEvent event){
        var project=event.getProject();if(project==null)return;
        FileDocumentManager.getInstance().saveAllDocuments();var state=GhiSettings.get(project).getState();
        final Path root;final GeneralCommandLine command;
        try{root=GhiCommand.directory(project.getBasePath(),state.directory);if(!Files.isDirectory(root))throw new IllegalArgumentException("Ghi project directory does not exist.");command=command(GhiCommand.executable(state.executable),root,state);}
        catch(RuntimeException error){Messages.showErrorDialog(project,error.getMessage(),title);return;}
        ProgressManager.getInstance().run(new Task.Backgroundable(project,title,false){
            @Override public void run(ProgressIndicator indicator){
                try{
                    var handler=new KillableColoredProcessHandler(command);
                    ProcessTerminatedListener.attach(handler,project);
                    ApplicationManager.getApplication().invokeLater(()->{
                        if(project.isDisposed()){handler.startNotify();handler.destroyProcess();return;}
                        new RunContentExecutor(project,handler).withTitle(title).withFilter(new GhiConsoleFilter(project,root)).run();
                    });
                }catch(Exception error){ApplicationManager.getApplication().invokeLater(()->{if(!project.isDisposed())Messages.showErrorDialog(project,error.getMessage(),title);});}
            }
        });
    }
    public static final class Race extends GhiTestAction {
        public Race(){super("Ghi Test with Race Detection");}
        @Override GeneralCommandLine command(String compiler,Path root,GhiSettings.State settings){return GhiTestCommand.race(compiler,root);}
    }
    public static final class Benchmark extends GhiTestAction {
        public Benchmark(){super("Ghi Benchmarks");}
        @Override GeneralCommandLine command(String compiler,Path root,GhiSettings.State settings){return GhiTestCommand.benchmark(compiler,root,settings);}
    }
}
