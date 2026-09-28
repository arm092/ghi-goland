package am.ghi.ide;
import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
@State(name="GhiSettings",storages=@Storage("ghi.xml"))
public final class GhiSettings implements PersistentStateComponent<GhiSettings.State> {
    public static final class State {
        public String executable="";
        public String directory="";
        public String arguments="";
        public String benchmarkPattern=".";
        public String benchmarkTime="1s";
        public String benchmarkCount="1";
        public boolean benchmarkMemory=true;
    }
    private State state=new State();
    public State getState(){return state;}
    public void loadState(State value){state=value;}
    public static GhiSettings get(Project project){return project.getService(GhiSettings.class);}
}
