package am.ghi.ide;

import com.goide.execution.GoRunUtil;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.xdebugger.*;
import org.jetbrains.annotations.NotNull;

import java.net.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Builds with DWARF, launches the bundled Delve, then opens a Ghi debug tab. */
public final class GhiDebugAction extends DumbAwareAction {
    @Override public ActionUpdateThread getActionUpdateThread(){return ActionUpdateThread.BGT;}
    @Override public void update(@NotNull AnActionEvent event){event.getPresentation().setEnabled(event.getProject()!=null);}
    @Override public void actionPerformed(@NotNull AnActionEvent event){
        var project=event.getProject();if(project==null)return;
        FileDocumentManager.getInstance().saveAllDocuments();
        var state=GhiSettings.get(project).getState();
        final Path directory;
        try{directory=GhiCommand.directory(project.getBasePath(),state.directory);}
        catch(RuntimeException error){Messages.showErrorDialog(project,error.getMessage(),"Ghi Debugger");return;}
        ProgressManager.getInstance().run(new Task.Backgroundable(project,"Build Ghi for debugging",false){
            @Override public void run(@NotNull ProgressIndicator indicator){
                try{
                    if(!Files.isDirectory(directory))throw new IllegalArgumentException("Project directory does not exist: "+directory);
                    var dlv=GoRunUtil.localDlv();
                    if(dlv==null||!dlv.isFile())throw new IllegalStateException("GoLand's Delve debugger is unavailable.");
                    boolean windows=System.getProperty("os.name").startsWith("Windows");
                    String name=directory.getFileName()+"-debug"+(windows?".exe":"");
                    Path executable=directory.resolve("bin").resolve(name);
                    var build=new ProcessBuilder(GhiCommand.executable(state.executable),"build","--debug","-o",executable.toString(),directory.toString())
                        .directory(directory.toFile()).redirectErrorStream(true).start();
                    String output=new String(build.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                    if(build.waitFor()!=0)throw new IllegalStateException("Ghi debug build failed:\n"+output);
                    GhiDebugNames names=GhiDebugNames.read(executable);
                    int port;
                    try(var reserved=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=reserved.getLocalPort();}
                    // GoLand 2025.1 bundles Delve 1.25, while Ghi builds with Go 1.26.
                    List<String> command=new ArrayList<>(List.of(dlv.getAbsolutePath(),"exec",executable.toString(),"--headless","--api-version=2","--listen=127.0.0.1:"+port,"--check-go-version=false"));
                    if(!state.arguments.isBlank()){command.add("--");command.addAll(ParametersListUtil.parse(state.arguments));}
                    var line=new GeneralCommandLine(command).withWorkDirectory(directory.toFile()).withCharset(StandardCharsets.UTF_8);
                    ApplicationManager.getApplication().invokeLater(()->{
                        if(project.isDisposed())return;
                        try{
                            var handler=new KillableColoredProcessHandler(line);
                            var console=TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
                            console.attachToProcess(handler);
                            InetSocketAddress address=new InetSocketAddress("127.0.0.1",port);
                            startSession(XDebuggerManager.getInstance(project),new XDebugProcessStarter(){
                                @Override public @NotNull XDebugProcess start(@NotNull XDebugSession session){
                                    var process=new GhiDebugProcess(session,handler,console,names,directory);process.connect(address);return process;
                                }
                            });
                        }catch(Exception error){Messages.showErrorDialog(project,error.getMessage(),"Ghi Debugger");}
                    });
                }catch(Exception error){ApplicationManager.getApplication().invokeLater(()->{
                    if(!project.isDisposed())Messages.showErrorDialog(project,error.getMessage(),"Ghi Debugger");
                });}
            }
        });
    }
    private static void startSession(XDebuggerManager manager,XDebugProcessStarter starter) throws Exception {
        try{
            // XDebugSessionBuilder was introduced after platform 251.
            Object builder=XDebuggerManager.class.getMethod("newSessionBuilder",XDebugProcessStarter.class).invoke(manager,starter);
            Class<?> builderApi=Class.forName("com.intellij.xdebugger.XDebugSessionBuilder");
            builder=builderApi.getMethod("sessionName",String.class).invoke(builder,"Ghi debug");
            builder=builderApi.getMethod("showTab",boolean.class).invoke(builder,true);
            builderApi.getMethod("startSession").invoke(builder);
        }catch(NoSuchMethodException missing){
            XDebuggerManager.class.getMethod("startSessionAndShowTab",String.class,com.intellij.execution.ui.RunContentDescriptor.class,XDebugProcessStarter.class)
                .invoke(manager,"Ghi debug",null,starter);
        }catch(InvocationTargetException error){
            if(error.getCause() instanceof Exception cause)throw cause;
            throw error;
        }
    }
}
