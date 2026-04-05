package modifier;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 変換処理のコンテキスト情報を保持するクラス。
 */
public class ModifierContext {
    private final List<String> sourceDirs = new ArrayList<>();
    private final List<String> classpathEntries = new ArrayList<>();
    private String destinationDir = "./output";
    private int complianceLevel = 21;
    private Charset encoding = StandardCharsets.UTF_8;
    private String newline = "\n"; // LF by default

    // 変換オプション
    private String replaceImportOldPrefix;
    private String replaceImportNewPrefix;

    private String relocateClassRegex;
    private String relocateClassNewPackage;

    private String removeParamFqcn;
    private String fqcnToImportRegex;

    // アノテーション操作
    private String addAnnotationFieldRegex;
    private String addAnnotationFieldFqcn;

    private String addAnnotationTypeRegex;
    private String addAnnotationTypeFqcn;

    private String replaceAnnotationOldRegex;
    private String replaceAnnotationNewFqcn;

    private String removeAnnotationRegex;

    private String addAnnotationByAnnotationTargetRegex;
    private String addAnnotationByAnnotationFqcn;

    public void addSourceDir(String dir) {
        this.sourceDirs.add(dir);
    }

    public List<String> getSourceDirs() {
        return sourceDirs;
    }

    public void addClasspathEntry(String path) {
        this.classpathEntries.add(path);
    }

    public List<String> getClasspathEntries() {
        return classpathEntries;
    }

    public String getDestinationDir() {
        return destinationDir;
    }

    public void setDestinationDir(String destinationDir) {
        this.destinationDir = destinationDir;
    }

    public int getComplianceLevel() {
        return complianceLevel;
    }

    public void setComplianceLevel(int complianceLevel) {
        this.complianceLevel = complianceLevel;
    }

    public Charset getEncoding() {
        return encoding;
    }

    public void setEncoding(Charset encoding) {
        this.encoding = encoding;
    }

    public String getNewline() {
        return newline;
    }

    public void setNewline(String newline) {
        if ("CRLF".equalsIgnoreCase(newline)) {
            this.newline = "\r\n";
        } else {
            this.newline = "\n";
        }
    }

    public void setReplaceImport(String oldPrefix, String newPrefix) {
        this.replaceImportOldPrefix = oldPrefix;
        this.replaceImportNewPrefix = newPrefix;
    }

    public String getReplaceImportOldPrefix() {
        return replaceImportOldPrefix;
    }

    public String getReplaceImportNewPrefix() {
        return replaceImportNewPrefix;
    }

    public void setRelocateClass(String regex, String newPackage) {
        this.relocateClassRegex = regex;
        this.relocateClassNewPackage = newPackage;
    }

    public String getRelocateClassRegex() {
        return relocateClassRegex;
    }

    public String getRelocateClassNewPackage() {
        return relocateClassNewPackage;
    }

    public String getRemoveParamFqcn() {
        return removeParamFqcn;
    }

    public void setRemoveParamFqcn(String removeParamFqcn) {
        this.removeParamFqcn = removeParamFqcn;
    }

    public String getFqcnToImportRegex() {
        return fqcnToImportRegex;
    }

    public void setFqcnToImportRegex(String fqcnToImportRegex) {
        this.fqcnToImportRegex = fqcnToImportRegex;
    }

    public void setAddAnnotationField(String regex, String fqcn) {
        this.addAnnotationFieldRegex = regex;
        this.addAnnotationFieldFqcn = fqcn;
    }

    public String getAddAnnotationFieldRegex() {
        return addAnnotationFieldRegex;
    }

    public String getAddAnnotationFieldFqcn() {
        return addAnnotationFieldFqcn;
    }

    public void setAddAnnotationType(String regex, String fqcn) {
        this.addAnnotationTypeRegex = regex;
        this.addAnnotationTypeFqcn = fqcn;
    }

    public String getAddAnnotationTypeRegex() {
        return addAnnotationTypeRegex;
    }

    public String getAddAnnotationTypeFqcn() {
        return addAnnotationTypeFqcn;
    }

    public void setReplaceAnnotation(String oldRegex, String newFqcn) {
        this.replaceAnnotationOldRegex = oldRegex;
        this.replaceAnnotationNewFqcn = newFqcn;
    }

    public String getReplaceAnnotationOldRegex() {
        return replaceAnnotationOldRegex;
    }

    public String getReplaceAnnotationNewFqcn() {
        return replaceAnnotationNewFqcn;
    }

    public String getRemoveAnnotationRegex() {
        return removeAnnotationRegex;
    }

    public void setRemoveAnnotationRegex(String removeAnnotationRegex) {
        this.removeAnnotationRegex = removeAnnotationRegex;
    }

    public void setAddAnnotationByAnnotation(String targetRegex, String fqcn) {
        this.addAnnotationByAnnotationTargetRegex = targetRegex;
        this.addAnnotationByAnnotationFqcn = fqcn;
    }

    public String getAddAnnotationByAnnotationTargetRegex() {
        return addAnnotationByAnnotationTargetRegex;
    }

    public String getAddAnnotationByAnnotationFqcn() {
        return addAnnotationByAnnotationFqcn;
    }

    /**
     * 何らかの変換処理オプションが設定されているかチェックする
     */
    public boolean hasAnyAction() {
        return replaceImportOldPrefix != null ||
                relocateClassRegex != null ||
                removeParamFqcn != null ||
                fqcnToImportRegex != null ||
                addAnnotationFieldRegex != null ||
                addAnnotationTypeRegex != null ||
                replaceAnnotationOldRegex != null ||
                removeAnnotationRegex != null ||
                addAnnotationByAnnotationTargetRegex != null;
    }
}
