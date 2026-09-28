package am.ghi.ide;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBTextField;
import javax.swing.*;
import java.awt.*;
import java.util.Objects;
public final class GhiConfigurable implements Configurable {
    private final Project project;
    private JBTextField executable,directory,arguments,benchmarkPattern,benchmarkTime,benchmarkCount;
    private JCheckBox benchmarkMemory;
    public GhiConfigurable(Project project){this.project=project;}
    public String getDisplayName(){return "Ghi";}
    public JComponent createComponent(){
        executable=new JBTextField();directory=new JBTextField();arguments=new JBTextField();
        benchmarkPattern=new JBTextField();benchmarkTime=new JBTextField();benchmarkCount=new JBTextField();benchmarkMemory=new JCheckBox("Report allocations (--benchmem)");
        JPanel fields=new JPanel(new GridLayout(0,1,0,6));
        fields.add(new JLabel("Compiler executable (blank: installed Ghi or PATH)"));fields.add(executable);
        fields.add(new JLabel("Project directory (blank: opened project; relative paths allowed)"));fields.add(directory);
        fields.add(new JLabel("Program arguments (Run only; quote arguments containing spaces)"));fields.add(arguments);
        fields.add(new JLabel("Benchmark name regex (--bench; default: .)"));fields.add(benchmarkPattern);
        fields.add(new JLabel("Benchmark duration or iterations (--benchtime; e.g. 1s or 100x)"));fields.add(benchmarkTime);
        fields.add(new JLabel("Benchmark repetitions (--count; positive integer)"));fields.add(benchmarkCount);
        fields.add(benchmarkMemory);
        JPanel panel=new JPanel(new BorderLayout());panel.add(fields,BorderLayout.NORTH);reset();return panel;
    }
    public boolean isModified(){var state=GhiSettings.get(project).getState();return executable!=null &&
        (!Objects.equals(executable.getText(),state.executable)||!Objects.equals(directory.getText(),state.directory)||!Objects.equals(arguments.getText(),state.arguments)
        ||!Objects.equals(benchmarkPattern.getText(),state.benchmarkPattern)||!Objects.equals(benchmarkTime.getText(),state.benchmarkTime)
        ||!Objects.equals(benchmarkCount.getText(),state.benchmarkCount)||benchmarkMemory.isSelected()!=state.benchmarkMemory);}
    public void apply() throws ConfigurationException {var state=GhiSettings.get(project).getState();try{GhiTestCommand.validateBenchmark(benchmarkPattern.getText(),benchmarkTime.getText(),benchmarkCount.getText());}catch(IllegalArgumentException error){throw new ConfigurationException(error.getMessage());}state.executable=executable.getText().trim();state.directory=directory.getText().trim();state.arguments=arguments.getText();state.benchmarkPattern=benchmarkPattern.getText().trim();state.benchmarkTime=benchmarkTime.getText().trim();state.benchmarkCount=benchmarkCount.getText().trim();state.benchmarkMemory=benchmarkMemory.isSelected();}
    public void reset(){if(executable==null)return;var state=GhiSettings.get(project).getState();executable.setText(state.executable);directory.setText(state.directory);arguments.setText(state.arguments);benchmarkPattern.setText(state.benchmarkPattern);benchmarkTime.setText(state.benchmarkTime);benchmarkCount.setText(state.benchmarkCount);benchmarkMemory.setSelected(state.benchmarkMemory);}
    public void disposeUIResources(){executable=null;directory=null;arguments=null;benchmarkPattern=null;benchmarkTime=null;benchmarkCount=null;benchmarkMemory=null;}
}
