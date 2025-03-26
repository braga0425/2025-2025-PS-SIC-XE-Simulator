package sicxesimulator.software.loader;

import sicxesimulator.hardware.Memory;
import sicxesimulator.data.ObjectFile;
import sicxesimulator.data.Symbol;
import sicxesimulator.data.SymbolTable;
import sicxesimulator.data.records.RelocationRecord;

/**
 * Carregador de módulos objeto na memória.
 * Se o objeto não estiver fullyRelocated,
 * este Loader aplica as relocações com base nos RelocationRecords.
 */
public class Loader {

    /**
     * Carrega um módulo objeto na memória, aplicando relocação se necessário.
     *
     * @param finalObject Módulo objeto a ser carregado
     * @param memory      Memória onde o módulo será carregado
     * @param baseAddress Endereço base onde o módulo será efetivamente colocado
     */
    public void loadObjectFile(
            ObjectFile finalObject,
            Memory memory,
            int baseAddress
    ) {
        byte[] code = finalObject.getObjectCode();
        int codeLength = code.length;

        // Verifica se cabe na memória
        if (baseAddress + codeLength > memory.getSize()) {
            throw new IllegalArgumentException(
                    "Programa não cabe na memória (base + code.length > memória)."
            );
        }

        // Copia o array de bytes do objeto para a memória
        copyCodeToMemory(memory, baseAddress, code);

        // Se não estiver 100% realocado, aplicamos as relocações
        if (!finalObject.isFullyRelocated()) {
            if (finalObject.getRelocationRecords() != null && !finalObject.getRelocationRecords().isEmpty()) {
                applyRelocations(memory, baseAddress, finalObject);
            }

            // Ajusta endereços na SymbolTable, para refletir o deslocamento baseAddress
            updateSymbolTableAddresses(finalObject.getSymbolTable(), baseAddress);

            // Marca que agora está realocado
            finalObject.setFullyRelocated(true);
        }
    }

    /**
     * Copia o código para a memória a partir de baseAddress.
     */
    private void copyCodeToMemory(Memory memory, int baseAddress, byte[] code) {
        for (int i = 0; i < code.length; i++) {
            memory.writeByte(baseAddress + i, code[i] & 0xFF);
        }
    }

    /**
     * Aplica todos os registros de relocação do ObjectFile na memória.
     */
    private void applyRelocations(Memory memory, int baseAddress, ObjectFile finalObject) {
        SymbolTable symTab = finalObject.getSymbolTable();
        for (RelocationRecord rec : finalObject.getRelocationRecords()) {
            applyRelocationInMemory(memory, baseAddress, rec, symTab);
        }
    }

    /**
     * Ajusta a tabela de símbolos, somando baseAddress a cada símbolo,
     * pois agora o programa está carregado efetivamente em baseAddress.
     */
    private void updateSymbolTableAddresses(SymbolTable symbolTable, int baseAddress) {
        for (var entry : symbolTable.getAllSymbols().entrySet()) {
            Symbol symbol = entry.getValue();
            symbol.address += baseAddress;
        }
    }

    /**
     * Aplica uma relocação individual: soma o endereço do símbolo ao valor
     * lido, e subtrai 3 se for PC-relative (formato 3).
     * Se fosse Formato 4, poderíamos subtrair 4, etc.
     */
    private void applyRelocationInMemory(
            Memory memory,
            int baseAddress,
            RelocationRecord rec,
            SymbolTable symTab
    ) {
        // offset é a posição (relativa ao startAddress do .obj),
        // mas já "fundido" na concatenação do Linker
        int offset = rec.offset();
        int length = rec.length();

        // Lê valor atual em 'offset' (na memória final)
        int val = 0;
        for (int i = 0; i < length; i++) {
            val = (val << 8) | (memory.readByte(baseAddress + offset + i) & 0xFF);
        }

        // Obter o endereço do símbolo
        Integer symAddr = symTab.getSymbolAddress(rec.symbol());
        if (symAddr == null) {
            throw new RuntimeException("Símbolo não encontrado no Loader: " + rec.symbol());
        }

        // Soma o endereço do símbolo ao valor
        int newVal = val + symAddr;

        // Se pcRelative => subtrai 3
        if (rec.pcRelative()) {
            newVal -= 3;
        }

        // Grava o resultado de volta na memória
        int tmp = newVal;
        for (int i = length - 1; i >= 0; i--) {
            memory.writeByte(baseAddress + offset + i, tmp & 0xFF);
            tmp >>>= 8;
        }
    }
}
