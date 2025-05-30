package sicxesimulator.simulation.model;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import sicxesimulator.simulation.interfaces.ModelListener;
import sicxesimulator.simulation.data.records.MemoryEntry;
import sicxesimulator.simulation.data.records.RegisterEntry;
import sicxesimulator.simulation.data.records.SymbolEntry;
import sicxesimulator.simulation.util.DialogUtil;
import sicxesimulator.hardware.cpu.Register;
import sicxesimulator.software.data.ObjectFile;
import sicxesimulator.software.assembler.Assembler;
import sicxesimulator.software.data.Symbol;
import sicxesimulator.software.linker.Linker;
import sicxesimulator.software.loader.Loader;
import sicxesimulator.software.macroprocessor.MacroProcessor;
import sicxesimulator.hardware.Machine;
import sicxesimulator.utils.*;

import java.io.*;
import java.util.*;

public class Model {
    private final Machine machine;
    private final Loader loader;
    private final Linker linker;
    private final Assembler assembler;
    private final MacroProcessor macroProcessor;
    private final BooleanProperty codeLoaded = new SimpleBooleanProperty(false);
    private final BooleanProperty simulationFinished = new SimpleBooleanProperty(false);
    private final List<ModelListener> listeners = new ArrayList<>();
    private ObjectFile lastLoadedCode;
    private LinkerMode linkerMode = LinkerMode.ABSOLUTO;

    public enum LinkerMode {
        ABSOLUTO,
        RELOCAVEL
    }

    public Model() {
        this.machine = new Machine();
        this.loader = new Loader();
        this.macroProcessor = new MacroProcessor();
        this.assembler = new Assembler();
        this.linker = new Linker();

        // Verifica a pasta apontada pela constante "SAVE_DIR" e carrega os arquivos de objeto
        loadObjectFilesFromSaveDir();
    }

    public LinkerMode getLinkerMode() {
        return linkerMode;
    }

    public void setLinkerMode(LinkerMode newMode) {
        this.linkerMode = newMode;
    }

    public int getMemorySize() {
        return machine.getMemorySize();
    }

    public Machine getMachine() {
        return machine;
    }

    public ObjectFile getLastLoadedCode() {
        return lastLoadedCode;
    }

    public List<MemoryEntry> getMemoryEntries() {
        List<MemoryEntry> entries = new ArrayList<>();
        var memory = machine.getMemory();
        for (int wordIndex = 0; wordIndex < memory.getAddressRange(); wordIndex++) {
            byte[] word = memory.readWord(wordIndex);
            String address = Convert.intToHexString24(wordIndex * 3);
            entries.add(new MemoryEntry(address, Convert.bytesToHex(word)));
        }
        return entries;
    }

    public List<RegisterEntry> getRegisterEntries() {
        List<RegisterEntry> entries = new ArrayList<>();
        var registers = machine.getControlUnit().getRegisterSet().getAllRegisters();
        for (Register register : registers) {
            String value;
            if (register.getName().equals("F")) {
                value = Convert.longToHexString48(register.getLongValue());
            } else {
                value = Convert.intToHexString24(register.getIntValue());
            }
            entries.add(new RegisterEntry(register.getName(), value));
        }

        return entries;
    }

    public List<SymbolEntry> getSymbolEntries() {
        List<SymbolEntry> entries = new ArrayList<>();
        ObjectFile objectFile = getLastLoadedCode();
        if (objectFile != null) {
            var symbolsMap = objectFile.getSymbolTable().getAllSymbols();
            symbolsMap.forEach((name, info) -> {
                String address = Convert.intToHexString24(info.address);
                entries.add(new SymbolEntry(name, address));
            });
        }
        return entries;
    }

    public BooleanProperty codeLoadedProperty() {
        return codeLoaded;
    }

    public BooleanProperty simulationFinishedProperty() {
        return simulationFinished;
    }

    public void setMemorySize(int newMemorySize) {
        machine.changeMemorySize(newMemorySize);
    }

    public void setCodeLoaded(boolean loaded) {
        codeLoaded.set(loaded);
    }

    public void setSimulationFinished(boolean finished) {
        simulationFinished.set(finished);
    }

    public List<String> processCodeMacros(List<String> rawSourceLines) throws IOException {
        // Usa a constante TEMP_DIR definida em Constants
        FileUtils.ensureDirectoryExists(Constants.TEMP_DIR);

        // Define os caminhos completos para os arquivos temporários
        String tempInputFile = Constants.TEMP_DIR + "/temp.asm";

        // Escreve o código fonte original no arquivo de entrada usando FileUtils
        FileUtils.writeFile(tempInputFile, String.join("\n", rawSourceLines));

        // Processa as macros: o MacroProcessor lê o arquivo de entrada e gera o arquivo expandido
        macroProcessor.process(tempInputFile, "MASMAPRG.ASM");

        // Lê o conteúdo do arquivo expandido usando FileUtils
        String expandedContent = FileUtils.readFile(Constants.TEMP_DIR + "/" + "MASMAPRG.ASM");

        return Arrays.asList(expandedContent.split("\\r?\\n"));
    }

    public ObjectFile assembleCode(List<String> rawSourceLines, List<String> preProcessedSourceCode) throws IOException {
        ObjectFile machineCode = assembler.assemble(rawSourceLines, preProcessedSourceCode);
        addAndSaveObjectFileToList(machineCode);

        return machineCode;
    }

    public ObjectFile linkObjectFiles(List<ObjectFile> files, int loadAddress, boolean fullRelocation, String linkedName) {
        ObjectFile linkedObj = linker.linkModules(files, fullRelocation, loadAddress, linkedName);
        addAndSaveObjectFileToList(linkedObj);
        return linkedObj;
    }

    public void runNextInstruction() {
        machine.runCycle();
    }

    public void restartMachine() {
        setCodeLoaded(false);
        setSimulationFinished(false);
        machine.reset();
    }

    public void loadProgramToMachine(ObjectFile selectedFile, int baseAddress) {
        if (selectedFile != null) {
            // Carrega o objeto na memória
            loader.loadObjectFile(selectedFile, machine.getMemory(), baseAddress);
            // Atualiza o PC para o startAddress definido no objeto final.
            // Assim, se o header indica E^000100, o PC passa a ser 0x100.
            machine.getControlUnit().setIntValuePC(selectedFile.getStartAddress());

            setCodeLoaded(true);
            lastLoadedCode = selectedFile;

            // Loga o estado logo após a carga para depuração.
            logDetailedState("Programa carregado em loadProgramToMachine()");

            notifyListeners();
        }
    }

    public void addAndSaveObjectFileToList(ObjectFile objectFile) {
        File savedDir = new File(Constants.SAVE_DIR);
        if (!savedDir.exists()) {
            if (!savedDir.mkdirs()) {
                DialogUtil.showError("Erro ao criar diretório para salvar arquivos.");
                return;
            }
        }

        // Use .meta em vez de .obj
        File saveFile = new File(savedDir, objectFile.getProgramName() + ".meta");

        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(saveFile))) {
            oos.writeObject(objectFile);
        } catch (IOException e) {
            DialogUtil.showError("Erro ao salvar o arquivo: " + e.getMessage());
        }

        notifyListeners();
    }

    public void loadObjectFilesFromSaveDir() {
        File savedDir = new File(Constants.SAVE_DIR);

        // Verifica se o diretório existe
        if (savedDir.exists() && savedDir.isDirectory()) {
            File[] files = savedDir.listFiles((dir, name) -> name.endsWith(".meta"));
            if (files != null) {
                for (File file : files) {
                    try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                        // Carrega o ObjectFile do arquivo
                        ois.readObject();
                    } catch (IOException | ClassNotFoundException e) {
                        // Agendar a exibição do erro na thread do JavaFX, se necessário
                        if (javafx.application.Platform.isFxApplicationThread()) {
                            DialogUtil.showError("Erro ao carregar arquivo: " + e.getMessage());
                        } else {
                            javafx.application.Platform.runLater(() ->
                                    DialogUtil.showError("Erro ao carregar arquivo: " + e.getMessage())
                            );
                        }
                    }
                }
            }
        }
    }

    public void deleteSavedProgram(ObjectFile objectFile) {
        File objFile = new File(Constants.SAVE_DIR, objectFile.getProgramName() + ".obj");

        // Verifica se o arquivo existe e o deleta (para os .obj)
        if (objFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            objFile.delete();
        }

        File metaFile = new File(Constants.SAVE_DIR, objectFile.getProgramName() + ".meta");

        // Verifica se o arquivo existe e o deleta (para os .meta)
        if (metaFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            metaFile.delete();
        }
    }

    public void logDetailedState(String contextMessage) {
        String objectCodeText = "(Nenhum objeto carregado)";
        Map<String, Integer> symbolMap = new HashMap<>();
        String sourceCodeText = "(Nenhum código fonte disponível)";

        if (lastLoadedCode != null) {
            objectCodeText = lastLoadedCode.getObjectCodeAsString();

            // Converte o SymbolTable para Map<String, Integer>
            Map<String, Symbol> symbols = lastLoadedCode.getSymbolTable().getAllSymbols();
            for (Map.Entry<String, Symbol> entry : symbols.entrySet()) {
                symbolMap.put(entry.getKey(), entry.getValue().address);
            }

            // Junta o código-fonte (rawSourceCode) em uma única String
            List<String> rawSource = lastLoadedCode.getRawSourceCode();
            if (rawSource != null && !rawSource.isEmpty()) {
                sourceCodeText = String.join("\n", rawSource);
            }
        }

        // Captura o histórico da execução (caso haja)
        String executionOutput = machine.getControlUnit().getExecutionHistory();
        if (executionOutput == null || executionOutput.isEmpty()) {
            executionOutput = "(Sem saída de execução)";
        }

        Logger.logMachineState(
                machine.getMemory(),
                machine.getControlUnit().getRegisterSet(),
                objectCodeText,
                symbolMap,
                sourceCodeText,
                executionOutput,
                contextMessage
        );
    }

    public void addListener(ModelListener listener) {
        listeners.add(listener);
    }

    private void notifyListeners() {
        for (ModelListener listener : listeners) {
            listener.onFilesUpdated();
        }
    }
}