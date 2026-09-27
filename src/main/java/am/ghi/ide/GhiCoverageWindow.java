package am.ghi.ide;

import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.*;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.content.ContentFactory;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.event.*;

public final class GhiCoverageWindow implements ToolWindowFactory {
    @Override public void createToolWindowContent(Project project,ToolWindow window){
        var service=GhiCoverageService.get(project);var panel=new JPanel(new BorderLayout());var status=new JLabel();
        var model=new AbstractTableModel(){
            final String[] columns={"Ghi source file","Statements covered","Statements total","Coverage"};
            @Override public int getRowCount(){return service.files().size();}
            @Override public int getColumnCount(){return columns.length;}
            @Override public String getColumnName(int column){return columns[column];}
            @Override public Class<?> getColumnClass(int column){return column==1?Long.class:column==2?Integer.class:String.class;}
            @Override public Object getValueAt(int row,int column){var file=service.files().get(row);return switch(column){case 0->service.relativePath(file.file());case 1->file.covered();case 2->file.statements().size();default->file.percentage();};}
        };
        var table=new JBTable(model);table.setAutoCreateRowSorter(true);
        table.addMouseListener(new MouseAdapter(){@Override public void mouseClicked(MouseEvent event){if(event.getClickCount()==2&&table.getSelectedRow()>=0){int index=table.convertRowIndexToModel(table.getSelectedRow());var file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(service.files().get(index).file());if(file!=null)new OpenFileDescriptor(project,file,0).navigate(true);}}});
        var clear=new JButton("Clear coverage");clear.addActionListener(event->service.clear("Coverage cleared."));
        panel.add(status,BorderLayout.NORTH);panel.add(new JBScrollPane(table),BorderLayout.CENTER);panel.add(clear,BorderLayout.SOUTH);
        service.bind(()->{status.setText(service.status());model.fireTableDataChanged();});
        window.getContentManager().addContent(ContentFactory.getInstance().createContent(panel,"",false));
    }
}
