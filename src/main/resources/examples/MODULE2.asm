MODULE2  START   200
         EXTDEF  BETA, ADDALPHA      ; Exporta BETA e a sub-rotina ADDALPHA
         EXTREF  ALPHA               ; Para poder ler/escrever ALPHA

BETA     WORD    10                  ; Uma variável global

ADDALPHA LDA     ALPHA               ; Lê ALPHA
         ADD     BETA                ; Soma BETA
         STA     ALPHA               ; Salva de volta em ALPHA
         RSUB                         ; Retorna para quem chamou
         END     MODULE2
