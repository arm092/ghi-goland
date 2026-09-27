package am.ghi.ide;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.JBColor;
import java.awt.Color;
import java.nio.file.Path;
import java.util.*;

/** Session-only coverage state. A new run or a source edit removes old annotations. */
public final class GhiCoverageService implements Disposable {
    private final Project project;
    private long generation;
    private volatile Path root;
    private GhiCoverageProfile result;
    private String status="Run Ghi tests with coverage to see statement coverage.";
    private final Map<Editor,List<RangeHighlighter>> highlights=new IdentityHashMap<>();
    private Runnable refresh=()->{};
    public GhiCoverageService(Project project){
        this.project=project;
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener(){
            @Override public void documentChanged(DocumentEvent event){var file=FileDocumentManager.getInstance().getFile(event.getDocument());if(relevant(file))invalidate();}
        },this);
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES,new BulkFileListener(){
            @Override public void after(List<? extends VFileEvent> events){for(var event:events)if(event.getPath().endsWith(".ghi")&&root!=null&&Path.of(event.getPath()).toAbsolutePath().normalize().startsWith(root)){invalidate();break;}}
        });
    }
    public static GhiCoverageService get(Project project){return project.getService(GhiCoverageService.class);}
    private boolean relevant(VirtualFile file){return file!=null&&root!=null&&file.getName().endsWith(".ghi")&&Path.of(file.getPath()).toAbsolutePath().normalize().startsWith(root);}
    private void invalidate(){if(ApplicationManager.getApplication().isDispatchThread())clear("Sources changed. Run tests with coverage again.");else ApplicationManager.getApplication().invokeLater(()->{if(!project.isDisposed())clear("Sources changed. Run tests with coverage again.");});}
    long begin(Path directory){clear("Running Ghi tests with coverage…");root=directory.toAbsolutePath().normalize();return generation;}
    void clear(String message){generation++;result=null;status=message;removeHighlights();refresh.run();}
    void finish(long token,GhiCoverageProfile profile,String error){
        if(token!=generation||project.isDisposed())return;
        removeHighlights();result=profile;status=error==null?"Statement coverage – successful test run":error;
        if(result!=null){for(Editor editor:EditorFactory.getInstance().getAllEditors())if(editor.getProject()==project)annotate(editor);}
        refresh.run();
        var window=ToolWindowManager.getInstance(project).getToolWindow("Ghi Coverage");if(window!=null)window.show();
    }
    String status(){return status;}
    List<GhiCoverageProfile.FileCoverage> files(){return result==null?List.of():result.files;}
    String relativePath(Path file){return result==null?file.toString():result.snapshot.root().relativize(file).toString();}
    void bind(Runnable refresh){this.refresh=refresh;refresh.run();}
    void annotate(Editor editor){
        if(editor.isDisposed()||editor.getProject()!=project||result==null)return;
        var file=FileDocumentManager.getInstance().getFile(editor.getDocument());if(file==null)return;
        var data=files().stream().filter(item->item.file().equals(Path.of(file.getPath()))).findFirst().orElse(null);
        if(data==null||!data.source().equals(editor.getDocument().getText()))return;
        release(editor);var added=new ArrayList<RangeHighlighter>();highlights.put(editor,added);
        Map<Integer,int[]> lines=new TreeMap<>();
        for(var statement:data.statements()){
            Color color=statement.covered()?new JBColor(0x388e3c,0x70b86e):new JBColor(0xc62828,0xe57373);
            var attributes=new TextAttributes();attributes.setEffectColor(color);attributes.setEffectType(EffectType.BOXED);
            var marker=editor.getMarkupModel().addRangeHighlighter(statement.offset(),statement.endOffset(),HighlighterLayer.ADDITIONAL_SYNTAX,attributes,HighlighterTargetArea.EXACT_RANGE);
            marker.setErrorStripeMarkColor(color);marker.setErrorStripeTooltip(statement.covered()?"Ghi statement executed":"Ghi statement not executed");added.add(marker);
            int[] count=lines.computeIfAbsent(statement.line(),ignored->new int[2]);count[1]++;if(statement.covered())count[0]++;
        }
        lines.forEach((line,count)->{
            Color color=count[0]==0?new JBColor(0xc62828,0xe57373):count[0]==count[1]?new JBColor(0x388e3c,0x70b86e):new JBColor(0xf9a825,0xffd54f);
            var marker=editor.getMarkupModel().addLineHighlighter(line,HighlighterLayer.ADDITIONAL_SYNTAX,null);
            marker.setLineMarkerRenderer((active,graphics,rectangle)->{graphics.setColor(color);graphics.fillRect(rectangle.x,rectangle.y,4,rectangle.height);});
            marker.setErrorStripeTooltip("Ghi statements executed: "+count[0]+"/"+count[1]);added.add(marker);
        });
    }
    void release(Editor editor){var markers=highlights.remove(editor);if(markers!=null)for(var marker:markers)if(marker.isValid())editor.getMarkupModel().removeHighlighter(marker);}
    private void removeHighlights(){for(Editor editor:new ArrayList<>(highlights.keySet()))release(editor);}
    @Override public void dispose(){removeHighlights();refresh=()->{};result=null;}
    public static final class Editors implements EditorFactoryListener {
        @Override public void editorCreated(EditorFactoryEvent event){var editor=event.getEditor();var project=editor.getProject();if(project!=null)ApplicationManager.getApplication().invokeLater(()->{if(!project.isDisposed())get(project).annotate(editor);});}
        @Override public void editorReleased(EditorFactoryEvent event){var project=event.getEditor().getProject();if(project!=null&&!project.isDisposed())get(project).release(event.getEditor());}
    }
}
