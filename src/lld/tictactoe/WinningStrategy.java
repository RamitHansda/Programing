package lld.tictactoe;

interface WinningStrategy {
    boolean checkWinner(Board board, Player player);
}

class RowWinningStrategy implements WinningStrategy {
    @Override
    public boolean checkWinner(Board board, Player player) {
        for (int row = 0; row < board.getSize(); row++) {
            boolean rowWin = true;
            for (int col = 0; col < board.getSize(); col++) {
                if (board.getCell(row, col).getSymbol() != player.getSymbol()) {
                    rowWin = false;
                    break;
                }
            }
            if (rowWin) return true;
        }
        return false;
    }
}

class ColumnWinningStrategy implements WinningStrategy {
    @Override
    public boolean checkWinner(Board board, Player player) {
        for (int col = 0; col < board.getSize(); col++) {
            boolean colWin = true;
            for (int row = 0; row < board.getSize(); row++) {
                if (board.getCell(row, col).getSymbol() != player.getSymbol()) {
                    colWin = false;
                    break;
                }
            }
            if (colWin) return true;
        }
        return false;
    }
}

class DiagonalWinningStrategy implements WinningStrategy {
    @Override
    public boolean checkWinner(Board board, Player player) {
        // Main diagonal
        boolean mainDiagWin = true;
        for (int i = 0; i < board.getSize(); i++) {
            if (board.getCell(i, i).getSymbol() != player.getSymbol()) {
                mainDiagWin = false;
                break;
            }
        }
        if (mainDiagWin) return true;

        // Anti-diagonal
        boolean antiDiagWin = true;
        for (int i = 0; i < board.getSize(); i++) {
            if (board.getCell(i, board.getSize() - 1 - i).getSymbol() != player.getSymbol()) {
                antiDiagWin = false;
                break;
            }
        }
        return antiDiagWin;
    }
}